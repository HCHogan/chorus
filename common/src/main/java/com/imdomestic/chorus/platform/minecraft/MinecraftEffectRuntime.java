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
    public record Failure(String message, List<DamageCapture.Observed> committedBeforeFailure, long unprocessedFacts, ShieldDamage.Commit committedShields) {
        public Failure { committedBeforeFailure = List.copyOf(committedBeforeFailure); Objects.requireNonNull(committedShields); }
    }
    private static final Map<ServerLevel, MinecraftEffectRuntime> LIVE = new ConcurrentHashMap<>();
    private final ServerLevel level;
    private final EffectSession session;
    private final NativeSource sources;
    private final CompiledEffects program;
    private final long originTick, originMicros;
    private List<RuleEngine.Signal> operationFacts;
    private EffectState shieldView;
    private final List<ShieldDamage.Write> shieldWrites = new ArrayList<>();
    private Optional<Failure> failure = Optional.empty();
    private boolean closed;
    private boolean transferringEquipment, refreshingEquipment;
    private final Map<java.util.UUID, ServerPlayer> equipmentOwners = new java.util.LinkedHashMap<>();

    private MinecraftEffectRuntime(ServerLevel level, CompiledEffects program, EffectState initial, EffectClock clock,
            Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world, NativeSource sources) {
        this.level = Objects.requireNonNull(level); this.sources = Objects.requireNonNull(sources);
        this.program = Objects.requireNonNull(program);
        this.originTick = level.getGameTime(); this.originMicros = initial.buffs().timeMicros();
        this.session = new EffectSession(program.engine(clock, 256), initial, request -> {
            if (operationFacts != null) throw new IllegalStateException("Reentrant world operation");
            operationFacts = new ArrayList<>();
            try {
                var actual = request.command() instanceof com.imdomestic.chorus.effect.weapon.WeaponReload.Verify query ? verifyReload(query) : world.apply(request);
                if (failure.isPresent()) throw new IllegalStateException("Native operation failed: " + failure.orElseThrow().message());
                var writes = takeWrites();
                return operationFacts.isEmpty() && writes.writes().isEmpty() ? actual : new RuleEngine.WorldReceipt(actual, operationFacts, writes);
            } finally { operationFacts = null; }
        });
    }
    public static MinecraftEffectRuntime install(ServerLevel level, CompiledEffects program, EffectState initial, EffectClock clock,
            Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world, NativeSource sources) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Runtime belongs to the server thread");
        var runtime = new MinecraftEffectRuntime(level, program, initial, clock, world, sources);
        if (LIVE.putIfAbsent(level, runtime) != null) throw new IllegalStateException("Level already has a rule runtime");
        try { DamageCapture.install(level, runtime); }
        catch (RuntimeException error) { LIVE.remove(level, runtime); throw error; }
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
            refreshEquipment(); return receipt;
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
            refreshEquipment(); return receipt;
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
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
            refreshEquipment();
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
        try { session.start(nowMicros(), signal); refreshEquipment(); }
        catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    @Override public void prepare() {
        thread();
        if (failure.isPresent() || session.running()) return;
        try { if (nowMicros() != state().engine().timeMicros()) session.observe(nowMicros(), List.of()); refreshEquipment(); }
        catch (RuntimeException error) { failed(error, List.of()); }
    }
    @Override public DamageCommand describe(LivingEntity target, DamageSource source, float amount) {
        thread();
        try { return Objects.requireNonNull(sources.describe(target, source, amount)); }
        catch (RuntimeException error) { failed(error, List.of()); return nativeSource(target, source, amount); }
    }
    @Override public ShieldDamage.Planned shields(DamageCommand command, double amount, DamageBasis basis) {
        thread();
        if (failure.isPresent()) return ShieldDamage.plan(amount, List.of());
        try {
            var view = shieldView == null ? state().engine().domain() : shieldView;
            var plan = program.shields(view, command, amount, basis);
            // The immutable shadow is visible to nested native hits before the outer receipt reaches the interpreter.
            shieldView = plan.commit().apply(view); shieldWrites.addAll(plan.commit().writes()); return plan;
        } catch (RuntimeException error) { failed(error, List.of()); return ShieldDamage.plan(amount, List.of()); }
    }
    private EffectState view() { return shieldView == null ? state().engine().domain() : shieldView; }
    @Override public Optional<CalculationProfile.Result> outgoing(DamageCommand command, double amount) {
        thread();
        if (failure.isPresent()) return Optional.empty();
        try { return program.outgoing(view(), command, amount); }
        catch (RuntimeException error) { failed(error, List.of()); return Optional.empty(); }
    }
    @Override public Optional<CalculationProfile.Result> defense(DamageCommand command, double amount) {
        thread();
        if (failure.isPresent()) return Optional.empty();
        try { return program.defense(view(), command, amount); }
        catch (RuntimeException error) { failed(error, List.of()); return Optional.empty(); }
    }
    private ShieldDamage.Commit takeWrites() {
        var result = new ShieldDamage.Commit(shieldWrites); shieldWrites.clear(); shieldView = null; return result;
    }
    @Override public void committed(List<DamageCapture.Observed> observations) {
        thread();
        var signals = observations.stream().flatMap(value -> DamageFacts.from(value.command(), value.receipt()).stream()).toList();
        if (failure.isPresent()) { countUnprocessed(signals.size()); return; }
        if (operationFacts != null) { operationFacts.addAll(signals); return; }
        if (signals.isEmpty()) return;
        var writes = takeWrites();
        try { session.observe(nowMicros(), signals, writes); refreshEquipment(); }
        catch (RuntimeException error) { failed(error, observations, writes); }
    }
    @Override public void failed(Throwable error, List<DamageCapture.Observed> committedBeforeFailure) {
        failed(error, committedBeforeFailure, new ShieldDamage.Commit(shieldWrites));
    }
    private void failed(Throwable error, List<DamageCapture.Observed> committedBeforeFailure, ShieldDamage.Commit committedShields) {
        if (failure.isPresent()) {
            countUnprocessed(committedBeforeFailure.stream().mapToLong(value -> DamageFacts.from(value.command(), value.receipt()).size()).sum());
            return;
        }
        failure = Optional.of(new Failure(error.getClass().getSimpleName() + ": " + error.getMessage(), committedBeforeFailure, 0, committedShields));
        Constants.LOG.error("Chorus rule runtime stopped for {}. Committed world changes are retained; no automatic replay.", level.dimension().identifier(), error);
    }
    private void countUnprocessed(long amount) {
        var previous = failure.orElseThrow();
        failure = Optional.of(new Failure(previous.message(), previous.committedBeforeFailure(), Math.addExact(previous.unprocessedFacts(), amount), previous.committedShields()));
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
    }
}
