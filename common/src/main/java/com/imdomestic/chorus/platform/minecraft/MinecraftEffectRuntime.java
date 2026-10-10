package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.effect.EffectClock;
import com.imdomestic.chorus.effect.EffectSession;
import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.combat.DamageBasis;
import com.imdomestic.chorus.effect.combat.DamageFacts;
import com.imdomestic.chorus.effect.combat.ShieldDamage;
import com.imdomestic.chorus.effect.combat.CombatCommit;
import com.imdomestic.chorus.effect.combat.ConsumptionReservations;
import com.imdomestic.chorus.effect.combat.BuffConsumption;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.rule.TimelineEngine;
import com.imdomestic.chorus.stat.CalculationProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** One explicitly installed, pinned ruleset per level. Native callbacks never recursively enter its pure interpreter. */
public final class MinecraftEffectRuntime implements DamageCapture.Observer, AutoCloseable {
    @FunctionalInterface public interface NativeSource {
        DamageCommand describe(LivingEntity target, DamageSource source, float amount);
    }
    public record Failure(String message, List<DamageCapture.Observed> committedBeforeFailure, long unprocessedFacts, ShieldDamage.Commit committedShields, CombatCommit committedCombat, List<ConsumptionReservations.Claim> pendingConsumptions, List<RuleEngine.Signal> committedCombatFacts) {
        public Failure { committedBeforeFailure = List.copyOf(committedBeforeFailure); Objects.requireNonNull(committedShields); Objects.requireNonNull(committedCombat); pendingConsumptions = List.copyOf(pendingConsumptions); committedCombatFacts = List.copyOf(committedCombatFacts); }
    }
    private static final Map<ServerLevel, MinecraftEffectRuntime> LIVE = new ConcurrentHashMap<>();
    private final ServerLevel level;
    private final EffectSession session;
    private final NativeSource sources;
    private final CompiledEffects program;
    private final MinecraftAttributeProjection nativeAttributes;
    private final MinecraftMovementProjection nativeMovement;
    private final MinecraftHorizontalSpeedProjection nativeHorizontalSpeed;
    private final long originTick, originMicros;
    private List<RuleEngine.Signal> operationFacts;
    private EffectState combatView;
    private final List<RuleEngine.Signal> combatFacts = new ArrayList<>();
    private final Map<String, ConsumptionReservations.Claim> reservations = new java.util.LinkedHashMap<>();
    private final java.util.ArrayDeque<String> activeDamage = new java.util.ArrayDeque<>();
    private final Map<String, DamageCapture.Observed> completedDamage = new java.util.LinkedHashMap<>();
    private final List<ShieldDamage.Write> shieldWrites = new ArrayList<>();
    private Optional<Failure> failure = Optional.empty();
    private boolean closed;
    private Optional<MinecraftNativeActions.Report> nativeActionReport=Optional.empty();
    private boolean transferringEquipment, refreshingEquipment;
    private final Map<java.util.UUID, ServerPlayer> equipmentOwners = new java.util.LinkedHashMap<>();

    private MinecraftEffectRuntime(ServerLevel level, CompiledEffects program, EffectState initial, EffectClock clock,
            Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world, NativeSource sources) {
        this.level = Objects.requireNonNull(level); this.sources = Objects.requireNonNull(sources);
        this.program = Objects.requireNonNull(program);
        this.nativeAttributes = new MinecraftAttributeProjection(level,program);
        this.nativeMovement = new MinecraftMovementProjection(level,program);
        this.nativeHorizontalSpeed = new MinecraftHorizontalSpeedProjection(level,program);
        this.originTick = level.getGameTime(); this.originMicros = initial.buffs().timeMicros();
        this.session = new EffectSession(program.engine(clock, 256), initial, request -> {
            if (operationFacts != null) throw new IllegalStateException("Reentrant world operation");
            operationFacts = new ArrayList<>();
            try {
                refreshProjections();
                var actual = switch (request.command()) {
                    case com.imdomestic.chorus.effect.weapon.WeaponReload.Verify query -> verifyReload(query);
                    case com.imdomestic.chorus.effect.weapon.InstantReload.Check query -> verifyInstantReload(query);
                    default -> world.apply(request);
                };
                if (failure.isPresent()) throw new IllegalStateException("Native operation failed: " + failure.orElseThrow().message());
                var writes = takeWrites();
                return operationFacts.isEmpty() && !writes.changed() ? actual : new RuleEngine.WorldReceipt(actual, operationFacts, writes);
            } finally { operationFacts = null; }
        });
    }
    public static MinecraftEffectRuntime install(ServerLevel level, CompiledEffects program, EffectState initial, EffectClock clock,
            Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world, NativeSource sources) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Runtime belongs to the server thread");
        var runtime = new MinecraftEffectRuntime(level, program, initial, clock, world, sources);
        if (LIVE.putIfAbsent(level, runtime) != null) throw new IllegalStateException("Level already has a rule runtime");
        try { DamageCapture.install(level, runtime); runtime.refreshProjections(); }
        catch (RuntimeException error) { try { runtime.close(); } catch(RuntimeException cleanup) { error.addSuppressed(cleanup); } throw error; }
        return runtime;
    }
    /** No weapon/ability credit is guessed from a player's currently held item. */
    public static DamageCommand nativeSource(LivingEntity target, DamageSource source, float amount) {
        String owner = source.getEntity() == null ? "" : source.getEntity().getUUID().toString();
        String type = source.typeHolder().unwrapKey().map(key -> key.identifier().toString()).orElse("chorus:unregistered_native_damage");
        String via = source.getDirectEntity() == null ? type : source.getDirectEntity().getUUID().toString();
        double requested = Float.isFinite(amount) ? Math.max(0, amount) : Float.MAX_VALUE;
        return new DamageCommand(target.getUUID().toString(), new BuffInstance.Origin(owner, via, "", ""), requested,
                type, Set.of("chorus:native_damage"), Set.of(), false);
    }
    public TimelineEngine.State<EffectState> state() { return session.state(); }
    public CompiledEffects program() { return program; }
    public Optional<MinecraftNativeActions.Report> nativeActionReport(){return nativeActionReport;}
    boolean authorizeNativeAction(LivingEntity actor,net.minecraft.world.entity.Entity victim,String attack,com.imdomestic.chorus.effect.input.ActionGate.Kind kind){
        thread();
        if(!program.hasActionGates(kind))return true;
        prepare();
        var input=MinecraftNativeActions.input(actor,victim,attack,kind);long now=view().buffs().timeMicros();
        if(actor.level()!=level||actor.isRemoved()||!actor.isAlive()){
            nativeActionReport=Optional.of(new MinecraftNativeActions.Report(now,input,MinecraftNativeActions.Outcome.INELIGIBLE,Optional.empty(),Optional.empty()));return false;
        }
        try{
            // A stopped runtime retains its committed restrictions; only successful queries authorize new attacks.
            var decision=program.checkAction(view(),kind,com.imdomestic.chorus.effect.input.ActionGate.Phase.START,input);
            nativeActionReport=Optional.of(new MinecraftNativeActions.Report(now,input,decision.allowed()?MinecraftNativeActions.Outcome.ALLOWED:MinecraftNativeActions.Outcome.RESTRICTED,Optional.of(decision),Optional.empty()));
            return decision.allowed();
        }catch(RuntimeException error){
            failed(error,List.of());
            nativeActionReport=Optional.of(new MinecraftNativeActions.Report(now,input,MinecraftNativeActions.Outcome.QUERY_FAILED,Optional.empty(),Optional.of(error.getClass().getSimpleName()+": "+error.getMessage())));return false;
        }
    }
    public List<MinecraftAttributeProjection.Report> nativeAttributeReport() { return nativeAttributes.reports(); }
    public List<MinecraftMovementProjection.Report> nativeMovementReport() { return nativeMovement.reports(); }
    public List<MinecraftHorizontalSpeedProjection.Report> nativeHorizontalSpeedReport() { return nativeHorizontalSpeed.reports(); }
    private void refreshProjections() { if(!closed&&failure.isEmpty()){nativeAttributes.reconcile(view());nativeMovement.reconcile(view());nativeHorizontalSpeed.reconcile(view());} }
    /** Capture before a host launches its projectile or detached delayed attack. The host retains the returned immutable data. */
    public com.imdomestic.chorus.effect.data.DamageSnapshot captureDamage(DamageCommand attack) {
        thread(); prepare();
        if (failure.isPresent()) throw new IllegalStateException("Cannot capture damage from a failed runtime");
        return program.captureDamage(view(), attack);
    }
    public static Optional<MinecraftEffectRuntime> installed(ServerLevel level) { return Optional.ofNullable(LIVE.get(level)); }
    public void bind(com.imdomestic.chorus.effect.EffectSource source) {
        var change = new com.imdomestic.chorus.effect.SourceChange(source.instance(), Optional.of(source));
        program.validateSourceChange(change); start(com.imdomestic.chorus.effect.SourceChange.bind(source));
    }
    public void unbind(String instance) {
        program.validateSourceChange(new com.imdomestic.chorus.effect.SourceChange(instance, Optional.empty()));
        start(com.imdomestic.chorus.effect.SourceChange.remove(instance));
    }
    public void replaceSources(com.imdomestic.chorus.effect.SourceBatch batch) {
        thread(); prepare(); program.validateSources(batch); batch.validateCurrent(view()); start(batch.signal());
    }
    /** Trusted selection boundary. Player unlocks and subclass compatibility belong to the host. */
    public void abilities(com.imdomestic.chorus.effect.ability.AbilityChange change) {
        thread(); prepare(); change.apply(view(), program); start(change.signal());
    }
    /** One input consumes at most one eligible loaded flight, nearest first with UUID tie-breaking. */
    public Optional<java.util.UUID> catchProjectile(ServerPlayer player) {
        thread(); prepare();
        if (player.level() != level || player.isRemoved() || !player.isAlive() || player.isSpectator()) throw new IllegalArgumentException("Player cannot catch projectiles here");
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Projectile interaction requires a healthy idle runtime");
        EffectProjectile selected = null; double closest = Double.POSITIVE_INFINITY;
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof EffectProjectile projectile)) continue;
            var distance = projectile.catchDistance(player, this); if (distance.isEmpty()) continue;
            double d = distance.getAsDouble();
            if (d < closest || d == closest && (selected == null || projectile.getUUID().compareTo(selected.getUUID()) < 0)) { selected = projectile; closest = d; }
        }
        return selected != null && selected.catchBy(player, this) ? Optional.of(selected.getUUID()) : Optional.empty();
    }
    /** Self-owned command/input entry. No client-supplied facts, definition, cost or target. */
    public com.imdomestic.chorus.effect.ability.AbilityUse.Receipt useAbility(ServerPlayer player, String slot) {
        thread(); prepare();
        if (player.level() != level || player.isRemoved() || !player.isAlive() || player.isSpectator()) throw new IllegalArgumentException("Player cannot use abilities here");
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Ability use requires a healthy idle runtime");
        String holder = player.getUUID().toString();
        var input = new com.imdomestic.chorus.effect.EffectEvent(holder, holder, new BuffInstance.Origin(holder, "", "", ""), java.util.Set.of(), java.util.Map.of(),
                java.util.Map.of("on_ground", player.onGround(), "sprinting", player.isSprinting(), "crouching", player.isCrouching()), java.util.Map.of());
        var request = new com.imdomestic.chorus.effect.ability.AbilityUse.Request(holder, slot, java.util.UUID.randomUUID().toString(), input);
        var before = view(); var resolved = program.useAbility(before, request);
        var receipt = (com.imdomestic.chorus.effect.ability.AbilityUse.Receipt) resolved.result();
        if (receipt.outcome() != com.imdomestic.chorus.effect.ability.AbilityUse.Outcome.ACCEPTED) return receipt;
        try {
            session.observe(nowMicros(), resolved.emitted(), new com.imdomestic.chorus.effect.ability.AbilityUse.Commit(before, resolved.state()));
            refreshEquipment(); refreshProjections(); return receipt;
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    /** Self-owned input: the server derives the weapon, ammunition and duration from its real container. */
    public com.imdomestic.chorus.effect.weapon.WeaponReload.Receipt reload(ServerPlayer player) {
        thread(); prepare();
        if (player.level() != level || player.isRemoved() || !player.isAlive() || player.isSpectator()) throw new IllegalArgumentException("Player cannot reload here");
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Reload requires a healthy idle runtime");
        String holder = player.getUUID().toString();
        var physical = PlayerEquipment.get(player).projection(program.equipment());
        if (!physical.equals(view().equipment().getOrDefault(holder, com.imdomestic.chorus.effect.equipment.Loadout.EMPTY))) throw new IllegalStateException("Reload equipment differs from physical ownership");
        trackEquipment(player);
        var before = view(); var resolved = program.reload(before, new com.imdomestic.chorus.effect.weapon.WeaponReload.Request(holder, java.util.UUID.randomUUID().toString()));
        var receipt = (com.imdomestic.chorus.effect.weapon.WeaponReload.Receipt) resolved.result();
        if (receipt.outcome() != com.imdomestic.chorus.effect.weapon.WeaponReload.Outcome.ACCEPTED) return receipt;
        try {
            session.observe(nowMicros(), resolved.emitted(), new com.imdomestic.chorus.effect.weapon.WeaponReload.Commit(before, resolved.state()));
            refreshEquipment(); refreshProjections(); return receipt;
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    /** Self-owned input: the server derives the weapon, ammunition and fire interval from its real container. */
    public com.imdomestic.chorus.effect.weapon.WeaponFire.Receipt fire(ServerPlayer player) {
        thread(); prepare();
        if (player.level() != level || player.isRemoved() || !player.isAlive() || player.isSpectator()) throw new IllegalArgumentException("Player cannot fire here");
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Fire requires a healthy idle runtime");
        String holder = player.getUUID().toString();
        var physical = PlayerEquipment.get(player).projection(program.equipment());
        if (!physical.equals(view().equipment().getOrDefault(holder, com.imdomestic.chorus.effect.equipment.Loadout.EMPTY))) throw new IllegalStateException("Fire equipment differs from physical ownership");
        trackEquipment(player);
        var before = view(); var resolved = program.fire(before, new com.imdomestic.chorus.effect.weapon.WeaponFire.Request(holder, java.util.UUID.randomUUID().toString()));
        var receipt = (com.imdomestic.chorus.effect.weapon.WeaponFire.Receipt) resolved.result();
        if (receipt.outcome() != com.imdomestic.chorus.effect.weapon.WeaponFire.Outcome.ACCEPTED) return receipt;
        try {
            session.observe(nowMicros(), resolved.emitted(), new com.imdomestic.chorus.effect.weapon.WeaponFire.Commit(before, resolved.state()));
            refreshEquipment(); refreshProjections(); return receipt;
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    private com.imdomestic.chorus.effect.weapon.InstantReload.Checked verifyInstantReload(com.imdomestic.chorus.effect.weapon.InstantReload.Check query) {
        ServerPlayer player;
        try { player = equipmentOwners.get(java.util.UUID.fromString(query.holder())); }
        catch (IllegalArgumentException invalid) { player = null; }
        boolean allowed = player != null && !player.isRemoved() && player.level() == level && player.isAlive() && !player.isSpectator()
                && PlayerEquipment.get(player).projection(program.equipment()).equals(query.equipment());
        return new com.imdomestic.chorus.effect.weapon.InstantReload.Checked(query, allowed);
    }
    private com.imdomestic.chorus.effect.weapon.WeaponReload.Verified verifyReload(com.imdomestic.chorus.effect.weapon.WeaponReload.Verify query) {
        var plan = query.plan(); ServerPlayer player;
        try { player = equipmentOwners.get(java.util.UUID.fromString(plan.holder())); }
        catch (IllegalArgumentException invalid) { player = null; }
        boolean allowed = player != null && !player.isRemoved() && player.level() == level && player.isAlive() && !player.isSpectator()
                && com.imdomestic.chorus.effect.weapon.WeaponReload.drawn(PlayerEquipment.get(player).projection(program.equipment())).filter(plan.gear()::equals).isPresent();
        return new com.imdomestic.chorus.effect.weapon.WeaponReload.Verified(query, allowed);
    }
    /** Trusted host API: the caller must validate physical item ownership before submitting metadata. */
    public void equip(com.imdomestic.chorus.effect.equipment.EquipmentChange change) {
        thread(); prepare(); program.changeEquipment(view(), change); start(change.signal());
    }
    /** Server container boundary: preflight all pure work, transfer ownership, then reconcile before any reaction. */
    public void commitEquipment(com.imdomestic.chorus.effect.equipment.EquipmentChange change, Runnable transferItems) {
        thread(); prepare();
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Equipment requires a healthy idle runtime");
        var transition = program.changeEquipment(view(), change);
        transferringEquipment = true;
        try { transferItems.run(); }
        catch (RuntimeException error) { failed(error, List.of()); throw error; }
        finally { transferringEquipment = false; }
        try {
            session.observe(nowMicros(), transition.emitted(), new com.imdomestic.chorus.effect.equipment.EquipmentChange.Commit(change));
            refreshEquipment(); refreshProjections();
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    public void trackEquipment(ServerPlayer player) {
        thread(); if (player.level() != level) throw new IllegalArgumentException("Equipment owner is in another level");
        equipmentOwners.put(player.getUUID(), player);
    }
    private void refreshEquipment() {
        if (refreshingEquipment || session.running() || failure.isPresent()) return;
        refreshingEquipment = true;
        try {
            for (var player : level.players()) if (!PlayerEquipment.get(player).isEmpty()) equipmentOwners.put(player.getUUID(), player);
            for (var entry : List.copyOf(equipmentOwners.entrySet())) {
                var player = entry.getValue(); boolean present = !player.isRemoved() && player.level() == level;
                var next = present && player.isAlive() ? PlayerEquipment.get(player).projection(program.equipment()) : com.imdomestic.chorus.effect.equipment.Loadout.EMPTY;
                String holder = entry.getKey().toString();
                var before = view().equipment().getOrDefault(holder, com.imdomestic.chorus.effect.equipment.Loadout.EMPTY);
                if (!before.equals(next)) equip(new com.imdomestic.chorus.effect.equipment.EquipmentChange(holder, before, next));
                // An empty container can acquire and lose an item within one reaction boundary (e.g. attach kills its owner).
                // Keep live owners tracked until they leave, so the final empty projection still reconciles that boundary.
                if (!present) equipmentOwners.remove(entry.getKey());
            }
        } finally { refreshingEquipment = false; }
    }
    public Optional<Failure> failure() { return failure; }
    private void thread() {
        if (closed || transferringEquipment || !level.getServer().isSameThread()) throw new IllegalStateException("Runtime is closed, transferring items, or accessed off its server thread");
    }
    public long nowMicros() {
        return Math.addExact(originMicros, Math.multiplyExact(Math.subtractExact(level.getGameTime(), originTick), 50_000));
    }
    public void start(RuleEngine.Signal signal) {
        thread();
        if (failure.isPresent()) throw new IllegalStateException("Failed runtime requires explicit recovery");
        try { session.start(nowMicros(), signal); refreshEquipment(); refreshProjections(); }
        catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    @Override public void prepare() {
        thread();
        if (failure.isPresent() || session.running()) return;
        try { if (nowMicros() != state().engine().timeMicros()) session.observe(nowMicros(), List.of()); refreshEquipment(); refreshProjections(); }
        catch (RuntimeException error) { failed(error, List.of()); }
    }
    @Override public DamageCommand describe(LivingEntity target, DamageSource source, float amount) {
        thread();
        try {
            var command = Objects.requireNonNull(sources.describe(target, source, amount));
            return failure.isPresent() ? command : program.prepareReactions(view(), command);
        }
        catch (RuntimeException error) { failed(error, List.of()); return nativeSource(target, source, amount); }
    }
    private EffectState attackView(DamageCommand command, String ownId) {
        return ConsumptionReservations.view(view(), command, ownId, List.copyOf(reservations.values()));
    }
    private EffectState attackView(DamageCommand command) { return attackView(command, activeDamage.isEmpty() ? "" : activeDamage.peek()); }
    @Override public DamageCommand begin(String id, DamageCommand command) {
        thread();
        if (failure.isPresent()) return command;
        try {
            if (reservations.containsKey(id)) throw new IllegalStateException("Duplicate active native damage identity");
            var prepared = program.prepareDamage(attackView(command, id), command);
            reservations.put(id, new ConsumptionReservations.Claim(id, prepared)); activeDamage.push(id); return prepared;
        } catch (RuntimeException error) { failed(error, List.of()); return command; }
    }
    @Override public DamageCommand revise(String id, DamageCommand command) {
        thread();
        if (failure.isPresent()) return command;
        try {
            if (!id.equals(activeDamage.peek())) throw new IllegalStateException("Native damage source revision is not current");
            var prepared = program.prepareDamage(attackView(command, id), command);
            reservations.put(id, new ConsumptionReservations.Claim(id, prepared)); return prepared;
        } catch (RuntimeException error) { failed(error, List.of()); return command; }
    }
    @Override public Optional<List<RuleEngine.Signal>> finished(String id, DamageCommand command, DamageReceipt receipt, boolean managed) {
        thread();
        if (failure.isPresent()) { if (id.equals(activeDamage.peek())) activeDamage.pop(); return Optional.empty(); }
        try {
            if (!id.equals(activeDamage.peek())) throw new IllegalStateException("Native damage receipts completed out of order");
            var consumed = BuffConsumption.finish(view(), command, receipt);
            combatView = consumed.state();
            completedDamage.put(id, new DamageCapture.Observed(command, receipt.withConsumptionFacts(consumed.emitted())));
            // Preserve the same damage-facts-before-lifecycle order as managed Action.complete.
            if (!managed) combatFacts.addAll(DamageFacts.from(command, receipt));
            if (!managed) combatFacts.addAll(consumed.emitted());
            reservations.remove(id); activeDamage.pop(); return Optional.of(consumed.emitted());
        } catch (RuntimeException error) { failed(error, List.of()); if (id.equals(activeDamage.peek())) activeDamage.pop(); return Optional.empty(); }
    }
    @Override public Optional<com.imdomestic.chorus.effect.buff.BuffObservation> observeBuffs(DamageCommand command) {
        thread();
        if (failure.isPresent()) return Optional.empty();
        return Optional.of(com.imdomestic.chorus.effect.buff.BuffObservation.capture(view().buffs(), command.source().owner(), command.target()));
    }
    @Override public Optional<com.imdomestic.chorus.effect.target.EntityObservation> observeEntities(DamageCommand command, LivingEntity target, DamageSource source) {
        thread();
        if (failure.isPresent()) return Optional.empty();
        var entities = new java.util.HashMap<String, Optional<com.imdomestic.chorus.effect.target.EntityQuery.View>>();
        var positions = new java.util.HashMap<com.imdomestic.chorus.effect.target.PositionQuery, Optional<com.imdomestic.chorus.effect.target.WorldPosition>>();
        observeEntity(command.target(), Optional.of(target), entities, positions);
        String owner = command.source().owner();
        if (!owner.isBlank() && !entities.containsKey(owner)) {
            java.util.UUID ownerId = null;
            try { ownerId = java.util.UUID.fromString(owner); } catch (IllegalArgumentException ignored) { /* Logical aliases need their adapter's resolver. */ }
            if (ownerId != null) {
                var actor = source.getEntity() != null && source.getEntity().getUUID().equals(ownerId) ? source.getEntity() : level.getEntity(ownerId);
                observeEntity(owner, actor instanceof LivingEntity living && living.level() == level && !living.isRemoved()
                        ? Optional.of(living) : Optional.empty(), entities, positions);
            }
        }
        return Optional.of(new com.imdomestic.chorus.effect.target.EntityObservation(view().buffs().timeMicros(), entities, positions));
    }
    private static void observeEntity(String reference, Optional<LivingEntity> entity,
            Map<String, Optional<com.imdomestic.chorus.effect.target.EntityQuery.View>> entities,
            Map<com.imdomestic.chorus.effect.target.PositionQuery, Optional<com.imdomestic.chorus.effect.target.WorldPosition>> positions) {
        entities.put(reference, entity.map(MinecraftWorldActions::observeEntity));
        for (var anchor : com.imdomestic.chorus.effect.target.TargetQuery.Anchor.values()) {
            positions.put(new com.imdomestic.chorus.effect.target.PositionQuery(reference, anchor), entity.map(e -> MinecraftWorldActions.observePosition(e, anchor)));
        }
    }
    @Override public void abandoned(String id) {
        thread();
        if (id.equals(activeDamage.peek())) activeDamage.pop();
        // Keep uncertain claims reserved until the enclosing boundary reports failure.
    }
    @Override public ShieldDamage.Planned shields(DamageCommand command, double amount, DamageBasis basis) {
        thread();
        if (failure.isPresent()) return ShieldDamage.plan(amount, List.of());
        try {
            var view = combatView == null ? state().engine().domain() : combatView;
            var plan = program.shields(view, command, amount, basis, attackView(command));
            // The immutable shadow is visible to nested native hits before the outer receipt reaches the interpreter.
            combatView = plan.commit().apply(view); shieldWrites.addAll(plan.commit().writes()); return plan;
        } catch (RuntimeException error) { failed(error, List.of()); return ShieldDamage.plan(amount, List.of()); }
    }
    private EffectState view() { return combatView == null ? state().engine().domain() : combatView; }
    @Override public Optional<CalculationProfile.Result> outgoing(DamageCommand command, double amount) {
        thread();
        if (failure.isPresent()) return Optional.empty();
        try { return program.outgoing(attackView(command), command, amount); }
        catch (RuntimeException error) { failed(error, List.of()); return Optional.empty(); }
    }
    @Override public Optional<CalculationProfile.Result> defense(DamageCommand command, double amount) {
        thread();
        if (failure.isPresent()) return Optional.empty();
        try { return program.defense(view(), command, amount); }
        catch (RuntimeException error) { failed(error, List.of()); return Optional.empty(); }
    }
    private CombatCommit combatCommit() { return new CombatCommit(state().engine().domain(), view()); }
    private CombatCommit takeWrites() {
        var result = combatCommit(); shieldWrites.clear(); combatView = null; completedDamage.clear(); return result;
    }
    @Override public void committed(List<DamageCapture.Observed> observations) {
        thread();
        var signals = List.copyOf(combatFacts); combatFacts.clear();
        if (failure.isPresent()) { countUnprocessed(observations.stream().mapToLong(value -> DamageFacts.from(value.command(), value.receipt()).size()).sum()); return; }
        if (operationFacts != null) { operationFacts.addAll(signals); return; }
        var shieldCommit = new ShieldDamage.Commit(shieldWrites); var writes = takeWrites();
        if (signals.isEmpty() && !writes.changed()) return;
        try { session.observe(nowMicros(), signals, writes); refreshEquipment(); refreshProjections(); }
        catch (RuntimeException error) { failed(error, observations, shieldCommit, writes, signals); }
    }
    @Override public void failed(Throwable error, List<DamageCapture.Observed> committedBeforeFailure) {
        if (failure.isPresent()) {
            countUnprocessed(committedBeforeFailure.stream().mapToLong(value -> DamageFacts.from(value.command(), value.receipt()).size()).sum());
            return;
        }
        var known = new java.util.LinkedHashMap<>(completedDamage);
        for (var observation : committedBeforeFailure) known.putIfAbsent(observation.receipt().damageId(), observation);
        var facts = new ArrayList<RuleEngine.Signal>();
        for (var observation : known.values()) {
            facts.addAll(DamageFacts.from(observation.command(), observation.receipt()));
            observation.receipt().consumptionFacts().ifPresent(facts::addAll);
        }
        failed(error, List.copyOf(known.values()), new ShieldDamage.Commit(shieldWrites), combatCommit(), facts);
    }
    private void failed(Throwable error, List<DamageCapture.Observed> committedBeforeFailure, ShieldDamage.Commit committedShields,
            CombatCommit committedCombat, List<RuleEngine.Signal> facts) {
        if (failure.isPresent()) {
            countUnprocessed(committedBeforeFailure.stream().mapToLong(value -> DamageFacts.from(value.command(), value.receipt()).size()).sum());
            return;
        }
        failure = Optional.of(new Failure(error.getClass().getSimpleName() + ": " + error.getMessage(), committedBeforeFailure, 0,
                committedShields, committedCombat, List.copyOf(reservations.values()), facts));
        Constants.LOG.error("Chorus rule runtime stopped for {}. Committed world changes are retained; no automatic replay.", level.dimension().identifier(), error);
    }
    private void countUnprocessed(long amount) {
        var previous = failure.orElseThrow();
        failure = Optional.of(new Failure(previous.message(), previous.committedBeforeFailure(), Math.addExact(previous.unprocessedFacts(), amount), previous.committedShields(),
                previous.committedCombat(), previous.pendingConsumptions(), previous.committedCombatFacts()));
    }
    public static void tick(ServerLevel level) {
        var runtime = LIVE.get(level);
        if (runtime == null || runtime.failure.isPresent()) return;
        try { runtime.prepare(); runtime.start(new RuleEngine.Signal("chorus:tick", RuleEngine.Empty.INSTANCE)); }
        catch (RuntimeException error) { runtime.failed(error, List.of()); }
    }
    public static void unload(ServerLevel level) { var runtime = LIVE.get(level); if (runtime != null) runtime.close(); }
    public static void stop(MinecraftServer server) {
        List.copyOf(LIVE.values()).stream().filter(runtime -> runtime.level.getServer() == server).forEach(MinecraftEffectRuntime::close);
        DamageCapture.clear(server);
    }
    @Override public void close() {
        if (closed) return;
        thread();
        if (session.running()) throw new IllegalStateException("Cannot detach a running runtime");
        closed = true; equipmentOwners.clear(); LIVE.remove(level, this); DamageCapture.remove(level, this);
        var constructs = new ArrayList<EffectConstruct>();
        for (var entity : level.getAllEntities()) if (entity instanceof EffectConstruct construct && construct.ownedBy(this)) constructs.add(construct);
        constructs.forEach(EffectConstruct::discard);
        RuntimeException cleanup=null;
        try { nativeAttributes.close(); } catch(RuntimeException error) { cleanup=error; }
        try { nativeMovement.close(); } catch(RuntimeException error) { if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error); }
        try { nativeHorizontalSpeed.close(); } catch(RuntimeException error) { if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error); }
        if(cleanup!=null)throw cleanup;
    }
}
