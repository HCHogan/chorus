package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.effect.EffectClock;
import com.imdomestic.chorus.effect.EffectSession;
import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.EntityObservation;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.effect.target.PositionQuery;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.actor.*;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.combat.DamageBasis;
import com.imdomestic.chorus.effect.combat.DamageFacts;
import com.imdomestic.chorus.effect.combat.ShieldDamage;
import com.imdomestic.chorus.effect.combat.CombatCommit;
import com.imdomestic.chorus.effect.combat.ConsumptionReservations;
import com.imdomestic.chorus.effect.combat.BuffConsumption;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.resource.ResourceReadView;
import com.imdomestic.chorus.effect.resource.ResourceRequests;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.rule.TimelineEngine;
import com.imdomestic.chorus.stat.CalculationProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
    private final Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> world;
    private final NativeSource sources;
    private final CompiledEffects program;
    private final MinecraftResourceRuntime resourceHost;
    private RuleEngine.WorldRequest resourceOperation;
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
    private int describingDamage;
    private Optional<MinecraftNativeActions.Report> nativeActionReport=Optional.empty();
    public record ProjectileTrackingReport(long timeMicros, EffectEvent input, ActionGate.Phase phase,
            Optional<ActionGate.Decision> decision, Optional<String> failure) {
        public ProjectileTrackingReport {
            Objects.requireNonNull(input); Objects.requireNonNull(phase); Objects.requireNonNull(decision); Objects.requireNonNull(failure);
            if (decision.isPresent() == failure.isPresent()) throw new IllegalArgumentException("Tracking query must have a decision or failure");
        }
    }
    private Optional<ProjectileTrackingReport> projectileTrackingReport = Optional.empty();
    private boolean transferringEquipment, refreshingEquipment;
    private final Map<java.util.UUID, ServerPlayer> equipmentOwners = new java.util.LinkedHashMap<>();
    private final Set<java.util.UUID> equipmentOwnerIds = new java.util.LinkedHashSet<>();
    private final Set<java.util.UUID> actorOwnerIds = new java.util.LinkedHashSet<>();
    private final Set<java.util.UUID> pendingActorValidation = new java.util.LinkedHashSet<>();
    private boolean refreshingActors, transferringActor;
    private final Set<java.util.UUID> abilityOwnerIds = new java.util.LinkedHashSet<>();
    private final Set<java.util.UUID> pendingAbilityValidation = new java.util.LinkedHashSet<>();
    private boolean refreshingAbilities, transferringAbilities;
    private record InputKey(java.util.UUID holder, String slot) {}
    private record HeldInput(ServerPlayer player, long gesture, String base, long startedMicros) {}
    private final Map<InputKey, HeldInput> abilityInputs = new java.util.LinkedHashMap<>();
    private record ChargeInput(ServerPlayer player, net.minecraft.server.network.ServerGamePacketListenerImpl connection, String token, java.util.function.BooleanSupplier held) {}
    private final Map<String, ChargeInput> chargeInputs = new java.util.LinkedHashMap<>();
    private final Set<EffectProjectile> projectiles = new java.util.LinkedHashSet<>();
    private final List<RuleEngine.Signal> nativeLifecycleFacts = new ArrayList<>();
    private List<RuleEngine.Signal> settlingLifecycleFacts = List.of();
    private final boolean observesLandings;
    boolean observesLandings() { return observesLandings; }

    /** Lifecycle and physical callbacks record committed facts; they never recursively execute a rule frame. */
    void recordNativeLifecycle(RuleEngine.Signal fact) {
        if (closed || MinecraftCheckpointLifecycle.deferStop(level.getServer())) return;
        ownerThread();
        if (fact.payload() instanceof com.imdomestic.chorus.effect.target.EntityLifecycle.Removed removed
                && removed.kind() == com.imdomestic.chorus.effect.target.EntityLifecycle.Kind.TRANSFERRED) {
            // The new dimension owns the current player file. Keep this dimension's historical
            // standing rules, but do not compare them with a legitimately changed returning profile.
            var holder = java.util.UUID.fromString(removed.reference().target());
            actorOwnerIds.remove(holder); pendingActorValidation.remove(holder);
            abilityOwnerIds.remove(holder); pendingAbilityValidation.remove(holder);
        }
        if (failure.isPresent()) { countUnprocessed(1); return; }
        nativeLifecycleFacts.add(fact);
    }
    private List<RuleEngine.Signal> takeNativeLifecycleFacts() {
        var facts = List.copyOf(nativeLifecycleFacts); nativeLifecycleFacts.clear(); return facts;
    }

    void trackProjectile(EffectProjectile projectile) { thread(); projectiles.add(projectile); }
    private void abandonProjectiles() { projectiles.forEach(EffectProjectile::abandon); projectiles.clear(); }
    private void maintainProjectiles() {
        // Contact continuations can launch more flights or fail the runtime; iterate the original generation only.
        for (var projectile : List.copyOf(projectiles)) if (!projectile.maintain(this)) projectiles.remove(projectile);
    }

    private MinecraftEffectRuntime(ServerLevel level, CompiledEffects program, EffectState initial, EffectClock clock,
            Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world, NativeSource sources, Optional<EffectSession.Checkpoint> checkpoint) {
        this(level,program,initial,clock,world,sources,checkpoint,null);
    }
    private MinecraftEffectRuntime(ServerLevel level, CompiledEffects program, EffectState initial, EffectClock clock,
            Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world, NativeSource sources, Optional<EffectSession.Checkpoint> checkpoint,
            MinecraftResourceRuntime resourceHost) {
        this(level,program,initial,clock,world,sources,checkpoint,resourceHost,Optional.empty());
    }
    private MinecraftEffectRuntime(ServerLevel level, CompiledEffects program, EffectState initial, EffectClock clock,
            Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world, NativeSource sources, Optional<EffectSession.Checkpoint> checkpoint,
            MinecraftResourceRuntime resourceHost,Optional<EffectSession> restoredSession) {
        this.level = Objects.requireNonNull(level); this.sources = Objects.requireNonNull(sources);this.world=Objects.requireNonNull(world);
        this.program = Objects.requireNonNull(program);this.resourceHost=resourceHost;
        if(program.resourceRouting()!=(resourceHost!=null))throw new IllegalArgumentException("Coordinated native programs require their resource host");
        this.observesLandings = program.program().bundles().stream().flatMap(b -> b.rules().stream())
                .anyMatch(r -> r.on().equals(com.imdomestic.chorus.effect.motion.GroundContact.LANDED));
        this.nativeAttributes = new MinecraftAttributeProjection(level,program);
        this.nativeMovement = new MinecraftMovementProjection(level,program);
        this.nativeHorizontalSpeed = new MinecraftHorizontalSpeedProjection(level,program);
        this.originTick = level.getGameTime(); this.originMicros = initial.buffs().timeMicros();
        this.session = restoredSession.orElseGet(()->checkpoint.map(saved -> EffectSession.restore(program, clock, 256, saved, this::executeWorld))
                .orElseGet(() -> new EffectSession(program, clock, 256, initial, this::executeWorld)));
        if(restoredSession.isPresent()&&!this.session.checkpoint().equals(checkpoint.orElseThrow()))throw new IllegalArgumentException("Prepared native session differs from its checkpoint");
    }
    RuleEngine.ActionResult executeWorld(RuleEngine.WorldRequest request) {
        if(resourceHost!=null)resourceHost.enteringRuntime();
        if(request.command() instanceof ResourceRequests.Request){
            if(resourceHost==null)throw new IllegalStateException("Native resource request has no coordinator");
            return resourceHost.execute(this,level,request);
        }
        if (operationFacts != null) throw new IllegalStateException("Reentrant world operation");
        operationFacts = new ArrayList<>();resourceOperation=request;
        try {
            refreshProjections();
            var actual = switch (request.command()) {
                case com.imdomestic.chorus.effect.weapon.WeaponReload.Verify query -> verifyReload(query);
                case com.imdomestic.chorus.effect.weapon.WeaponHandling.Apply command -> applyHandling(command);
                case com.imdomestic.chorus.effect.weapon.WeaponAim.Verify command -> verifyAim(command);
                case com.imdomestic.chorus.effect.weapon.WeaponCharge.Verify command -> verifyCharge(command);
                case com.imdomestic.chorus.effect.weapon.InstantReload.Check query -> verifyInstantReload(query);
                default -> world.apply(request);
            };
            if (failure.isPresent()) throw new IllegalStateException("Native operation failed: " + failure.orElseThrow().message());
            operationFacts.addAll(takeNativeLifecycleFacts());
            var writes = takeWrites();
            return operationFacts.isEmpty() && !writes.changed() ? actual : new RuleEngine.WorldReceipt(actual, operationFacts,
                    com.imdomestic.chorus.effect.target.EntityAttachments.commit(writes, operationFacts));
        } finally { operationFacts = null;resourceOperation=null; }
    }
    public static MinecraftEffectRuntime install(ServerLevel level, CompiledEffects program, EffectState initial, EffectClock clock,
            Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world, NativeSource sources) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Runtime belongs to the server thread");
        MinecraftCheckpointBoundary.changingRuntime(level.getServer());
        var runtime = new MinecraftEffectRuntime(level, program, initial, clock, world, sources, Optional.empty());
        return attach(runtime);
    }
    /** Host metadata must match the corresponding world save; a clock match alone is not a world transaction. */
    public record Checkpoint(String dimension, long gameTime, EffectSession.Checkpoint effects, Set<java.util.UUID> equipmentOwners, Set<java.util.UUID> actorOwners, Set<java.util.UUID> abilityOwners) {
        public Checkpoint(String dimension, long gameTime, EffectSession.Checkpoint effects, Set<java.util.UUID> equipmentOwners, Set<java.util.UUID> actorOwners) {
            this(dimension, gameTime, effects, equipmentOwners, actorOwners, Set.of());
        }
        public Checkpoint(String dimension, long gameTime, EffectSession.Checkpoint effects, Set<java.util.UUID> equipmentOwners) {
            this(dimension, gameTime, effects, equipmentOwners, Set.of());
        }
        public Checkpoint {
            net.minecraft.resources.Identifier.parse(dimension); Objects.requireNonNull(effects);
            equipmentOwners = Set.copyOf(equipmentOwners);
            actorOwners = Set.copyOf(actorOwners); abilityOwners = Set.copyOf(abilityOwners);
            if (gameTime < 0) throw new IllegalArgumentException("Invalid saved world clock");
        }
    }
    public Checkpoint checkpoint() {
        checkpointBoundary();
        prepare(); // Settle the current world tick before pinning the relationship between the two clocks.
        return settledCheckpoint();
    }
    /** The server-wide boundary settles every dimension before capturing any final snapshot. */
    Checkpoint settledCheckpoint() {
        checkpointBoundary();
        if (!nativeLifecycleFacts.isEmpty()) throw new IllegalStateException("Checkpoint has unprocessed native lifecycle facts");
        return new Checkpoint(level.dimension().identifier().toString(), level.getGameTime(), session.checkpoint(), equipmentOwnerIds, actorOwnerIds, abilityOwnerIds);
    }
    private void checkpointBoundary() {
        thread();
        if (failure.isPresent() || session.running() || refreshingEquipment || refreshingActors || transferringActor || refreshingAbilities || transferringAbilities || describingDamage != 0 || operationFacts != null || combatView != null
                || !activeDamage.isEmpty() || !reservations.isEmpty() || !completedDamage.isEmpty() || !shieldWrites.isEmpty() || !combatFacts.isEmpty())
            throw new IllegalStateException("Checkpoint requires a settled Minecraft world boundary");
    }
    public static MinecraftEffectRuntime restore(ServerLevel level, CompiledEffects program, Checkpoint checkpoint, EffectClock clock,
            Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world, NativeSource sources) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Runtime belongs to the server thread");
        MinecraftCheckpointBoundary.changingRuntime(level.getServer());
        return restoreDetached(level,program,checkpoint,clock,world,sources,null,true,Optional.empty());
    }
    static MinecraftEffectRuntime coordinated(ServerLevel level,CompiledEffects program,EffectState initial,EffectClock clock,
            Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> world,NativeSource sources,MinecraftResourceRuntime host,Optional<Checkpoint> saved){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Runtime belongs to the server thread");
        return saved.map(checkpoint->restoreDetached(level,program,checkpoint,clock,world,sources,host,false,Optional.empty()))
                .orElseGet(()->new MinecraftEffectRuntime(level,program,initial,clock,world,sources,Optional.empty(),host));
    }
    private static MinecraftEffectRuntime restoreDetached(ServerLevel level,CompiledEffects program,Checkpoint checkpoint,EffectClock clock,
            Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> world,NativeSource sources,MinecraftResourceRuntime host,boolean attach,Optional<EffectSession> restoredSession){
        if (!level.dimension().identifier().toString().equals(checkpoint.dimension()) || level.getGameTime() != checkpoint.gameTime())
            throw new IllegalArgumentException("Checkpoint belongs to a different dimension or world save clock");
        if (!program.program().equals(checkpoint.effects().program())) throw new IllegalArgumentException("Checkpoint catalogue content differs");
        validateCheckpointEquipment(level, program, checkpoint);
        validateCheckpointActors(level, program, checkpoint);
        validateCheckpointAbilities(level, program, checkpoint);
        var runtime = new MinecraftEffectRuntime(level, program, checkpoint.effects().timeline().domain(), clock, world, sources, Optional.of(checkpoint.effects()),host,restoredSession);
        runtime.equipmentOwnerIds.addAll(checkpoint.equipmentOwners());
        runtime.actorOwnerIds.addAll(checkpoint.actorOwners());
        runtime.pendingActorValidation.addAll(checkpoint.actorOwners());
        level.players().forEach(player -> runtime.pendingActorValidation.remove(player.getUUID()));
        runtime.abilityOwnerIds.addAll(checkpoint.abilityOwners());
        runtime.pendingAbilityValidation.addAll(checkpoint.abilityOwners());
        level.players().forEach(player -> runtime.pendingAbilityValidation.remove(player.getUUID()));
        // The very first clock advance can finish a saved phase before refreshEquipment runs.
        // Reconnect the already-validated live containers without emitting equipment/source facts.
        for (var player : level.players()) if (checkpoint.equipmentOwners().contains(player.getUUID()))
            runtime.equipmentOwners.put(player.getUUID(), player);
        return attach?attach(runtime):runtime;
    }
    static MinecraftEffectRuntime connectRestored(ServerLevel level,CompiledEffects program,Checkpoint checkpoint,EffectClock clock,
            Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> world,NativeSource source,MinecraftResourceRuntime host,EffectSession session){
        return restoreDetached(level,program,checkpoint,clock,world,source,host,false,Optional.of(session));
    }
    EffectSession resourceSession(){return session;}
    static void attachCoordinated(MinecraftEffectRuntime runtime){attach(runtime);}
    void requireResourceDetach(){if(!closed){ownerThread();MinecraftCheckpointBoundary.enteringRuntime(level.getServer(),this);if(session.running())throw new IllegalStateException("Cannot detach a running resource runtime");}}
    static void validateCheckpointEquipment(ServerLevel level, CompiledEffects program, Checkpoint checkpoint) {
        // A mismatch with an already-present player's physical container is not an equipment-change event to replay.
        for (var player : level.players()) {
            if (!checkpoint.equipmentOwners().contains(player.getUUID()) && PlayerEquipment.get(player).isEmpty() && PlayerEquipment.get(player).grants().isEmpty()) continue;
            var saved = checkpoint.effects().timeline().domain().equipment().getOrDefault(player.getUUID().toString(), com.imdomestic.chorus.effect.equipment.Loadout.EMPTY);
            if (!saved.equals(PlayerEquipment.get(player).projection(program.equipment())))
                throw new IllegalArgumentException("Checkpoint equipment differs from the current player container: " + player.getUUID());
        }
    }
    static void validateCheckpointActors(ServerLevel level, CompiledEffects program, Checkpoint checkpoint) {
        for (var player : level.players()) {
            if (!checkpoint.actorOwners().contains(player.getUUID()) && !PlayerActor.get(player).configured()) continue;
            var saved = Optional.ofNullable(checkpoint.effects().timeline().domain().actors().get(player.getUUID().toString()));
            if (!saved.equals(PlayerActor.get(player).projection(program, player.getUUID().toString())))
                throw new IllegalArgumentException("Checkpoint actor differs from the current player configuration: " + player.getUUID());
        }
    }
    static void validateCheckpointAbilities(ServerLevel level, CompiledEffects program, Checkpoint checkpoint) {
        for (var player : level.players()) {
            if (!checkpoint.abilityOwners().contains(player.getUUID()) && !PlayerAbilities.get(player).configured()) continue;
            var saved = checkpoint.effects().timeline().domain().abilities().getOrDefault(player.getUUID().toString(), AbilityLoadout.EMPTY);
            if (!saved.equals(PlayerAbilities.get(player).projection(program)))
                throw new IllegalArgumentException("Checkpoint abilities differ from the current player selection: " + player.getUUID());
        }
    }
    private static MinecraftEffectRuntime attach(MinecraftEffectRuntime runtime) {
        var level = runtime.level;
        if (LIVE.putIfAbsent(level, runtime) != null) throw new IllegalStateException("Level already has a rule runtime");
        try { DamageCapture.install(level, runtime); runtime.refreshProjections(); }
        catch (RuntimeException error) {
            if(runtime.resourceHost==null)try { runtime.close(); } catch(RuntimeException cleanup) { error.addSuppressed(cleanup); }
            else runtime.failed(error,List.of());
            throw error;
        }
        return runtime;
    }
    /** No weapon/ability credit is guessed from a player's currently held item. */
    public static DamageCommand nativeSource(LivingEntity target, DamageSource source, float amount) {
        String owner = source.getEntity() == null ? "" : source.getEntity().getUUID().toString();
        String type = source.typeHolder().unwrapKey().map(key -> key.identifier().toString()).orElse("chorus:unregistered_native_damage");
        String via = source.getDirectEntity() == null ? type : source.getDirectEntity().getUUID().toString();
        double requested = Float.isFinite(amount) ? Math.max(0, amount) : Float.MAX_VALUE;
        return new DamageCommand(target.getUUID().toString(), new BuffInstance.Origin(owner, via, "", "", MinecraftNativeActions.sourceTags(source.getEntity())), requested,
                type, Set.of("chorus:native_damage"), Set.of(), false);
    }
    public TimelineEngine.State<EffectState> state() { return session.state(); }
    public CompiledEffects program() { return program; }
    public ServerLevel level() { return level; }
    public Optional<MinecraftNativeActions.Report> nativeActionReport(){return nativeActionReport;}
    /** Last actual tracking query, separate from input authorization and collision/damage receipts. */
    public Optional<ProjectileTrackingReport> projectileTrackingReport() { return projectileTrackingReport; }
    boolean authorizeProjectileTracking(ProjectileFlight.Launch launch, EffectProjectile projectile, LivingEntity candidate,
            boolean acquire, boolean redirect, long ageMicros, double distance) {
        thread();
        if (closed || failure.isPresent()) return false;
        if (!program.hasActionGates(ActionGate.Kind.PROJECTILE_TRACKING)) return true;
        var input = new EffectEvent(launch.owner(), candidate.getUUID().toString(), launch.origin(), Set.of("chorus:projectile_tracking"),
                Map.of("tracking_age", new com.imdomestic.chorus.stat.Measure(ageMicros / 1_000_000.0, com.imdomestic.chorus.stat.Unit.SECOND),
                        "tracking_distance", new com.imdomestic.chorus.stat.Measure(distance, com.imdomestic.chorus.stat.Unit.METER)),
                Map.of("tracking_redirect", redirect), Map.of("projectile", projectile.getUUID().toString(), "emitter", launch.emitter(), "tracking_owner_kind", projectile.launchOwnerKind()));
        return authorizeProjectileTracking(input, candidate, acquire ? ActionGate.Phase.START : ActionGate.Phase.CONTINUE, List.of(launch.owner(), launch.emitter()));
    }
    boolean authorizeProjectileTracking(EffectEvent input, LivingEntity candidate, ActionGate.Phase phase, List<String> actors) {
        thread();
        if (closed || failure.isPresent()) return false;
        String victim = candidate.getUUID().toString();
        long now = view().buffs().timeMicros();
        try {
            var entities = new HashMap<String, Optional<EntityQuery.View>>();
            var positions = new HashMap<PositionQuery, Optional<WorldPosition>>();
            observeEntity(victim, Optional.of(candidate), entities, positions);
            for (String id : actors) if (!id.isBlank() && !entities.containsKey(id)) {
                net.minecraft.world.entity.Entity entity = null;
                try { entity = level.getEntity(java.util.UUID.fromString(id)); } catch (IllegalArgumentException ignored) { /* Logical identities remain explicitly unavailable. */ }
                observeEntity(id, entity instanceof LivingEntity living && !living.isRemoved() && living.level() == level
                        ? Optional.of(living) : Optional.empty(), entities, positions);
            }
            input = input.withObservedEntities(Optional.of(new EntityObservation(now, entities, positions)));
            var decision = gate(ActionGate.Kind.PROJECTILE_TRACKING, phase, input);
            projectileTrackingReport = Optional.of(new ProjectileTrackingReport(now, input, phase, Optional.of(decision), Optional.empty()));
            return decision.allowed();
        } catch (RuntimeException error) {
            projectileTrackingReport = Optional.of(new ProjectileTrackingReport(now, input, phase, Optional.empty(), Optional.of(error.getClass().getSimpleName() + ": " + error.getMessage())));
            failed(error, List.of());
            return false;
        }
    }
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
            var decision=gate(kind,com.imdomestic.chorus.effect.input.ActionGate.Phase.START,input);
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
    private Optional<ResourceReadView> resources(EffectState state){
        return resourceHost==null?Optional.empty():Optional.of(resourceHost.view(this,level,resourceOperation).withLocalState(state));
    }
    private <T> T query(EffectState state,Function<EffectState,T> local,Function<ResourceReadView,T> coordinated){
        return resources(state).map(coordinated).orElseGet(()->local.apply(state));
    }
    private ActionGate.Decision gate(ActionGate.Kind kind,ActionGate.Phase phase,EffectEvent input){
        return query(view(),s->program.checkAction(s,kind,phase,input),v->program.checkAction(v,kind,phase,input));
    }
    private void localAbilityOwner(String holder){
        resources(view()).ifPresent(v->{if(!v.owner(holder).domain().equals(v.domain()))throw new IllegalStateException("Native ability configuration and input require the local resource owner");});
    }
    private RuleEngine.Local<EffectState> abilityChange(AbilityChange change){
        localAbilityOwner(change.holder());return query(view(),s->program.changeAbilities(s,change),v->program.changeAbilities(v,change));
    }
    private RuleEngine.Local<EffectState> equipmentChange(com.imdomestic.chorus.effect.equipment.EquipmentChange change){
        return query(view(),s->program.changeEquipment(s,change),v->program.changeEquipment(v,change));
    }
    private EffectSession.DomainUpdate preparedPublication(RuleEngine.Local<EffectState> transition){
        return resourceHost==null?null:session.prepareInboxDomain(state(),transition.state(),transition.emitted());
    }
    private void publish(RuleEngine.Local<EffectState> transition,RuleEngine.Payload legacy,EffectSession.DomainUpdate prepared){
        if(resourceHost==null)session.observe(nowMicros(),transition.emitted(),legacy);
        else{EffectSession.commitDomains(List.of(prepared));session.drain();}
    }
    boolean canCollectPickup(com.imdomestic.chorus.effect.object.WorldPickup.Spawn pickup){
        thread();return query(view(),s->program.canCollectPickup(s,pickup),v->program.canCollectPickup(v,pickup));
    }
    CalculationProfile.Result calculate(String holder,EffectEvent event,String profile,com.imdomestic.chorus.stat.Measure input){
        thread();var state=view();return program.calculate(state,holder,event,profile,input,List.of(),resources(state));
    }
    public com.imdomestic.chorus.effect.ammo.AmmoCapacity.View ammoCapacity(String instance){
        thread();var state=view();var account=state.ammunition().get(instance);if(account==null)throw new IllegalArgumentException("Missing ammunition account: "+instance);
        return program.ammoCapacity(state,account,com.imdomestic.chorus.effect.data.NumericQuery.Path.empty(),resources(state));
    }
    private void refreshProjections() {
        if(!closed&&failure.isEmpty()){
            var state=view();var resources=resources(state);
            if(resources.isPresent()){var v=resources.orElseThrow();nativeAttributes.reconcile(v);nativeMovement.reconcile(v);nativeHorizontalSpeed.reconcile(v);}
            else{nativeAttributes.reconcile(state);nativeMovement.reconcile(state);nativeHorizontalSpeed.reconcile(state);}
        }
    }
    /** Called after native attributes/UUID load, before vanilla clamps saved Health. No rules or world actions run here. */
    public static void beforeLoadedHealth(LivingEntity entity,float health) {
        if(!(entity.level() instanceof ServerLevel level))return;
        var runtime=LIVE.get(level);if(runtime==null)return;
        runtime.ownerThread(); // Loading NBT may need the already-pinned projection while a native save drains chunk work.
        if(!runtime.nativeAttributes.tracks(runtime.view(),entity))return;
        if(runtime.failure.isPresent())throw new IllegalStateException("Cannot load projected attributes from a failed runtime");
        try {
            var state=runtime.view();var resources=runtime.resources(state);
            if(resources.isPresent())runtime.nativeAttributes.beforeLoadedHealth(resources.orElseThrow(),entity,health);
            else runtime.nativeAttributes.beforeLoadedHealth(state,entity,health);
        }
        catch(RuntimeException error){runtime.failed(error,List.of());throw error;}
    }
    /** Capture before a host launches its projectile or detached delayed attack. The host retains the returned immutable data. */
    public com.imdomestic.chorus.effect.data.DamageSnapshot captureDamage(DamageCommand attack) {
        thread(); prepare();
        if (failure.isPresent()) throw new IllegalStateException("Cannot capture damage from a failed runtime");
        var state=view();return program.captureDamage(state, attack,resources(state));
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
    /** Trusted owner enrollment; standing rules persist independently of ability and equipment selections. */
    public void actor(com.imdomestic.chorus.effect.actor.ActorChange change) {
        thread(); prepare(); change.apply(view(), program); start(change.signal());
    }
    void prepareActorChange() {
        thread(); prepare();
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Actor configuration requires a healthy idle runtime");
    }
    /** Publish persistent player metadata and the domain projection before running any attached/detached action. */
    void commitPlayerActor(ServerPlayer player, ActorChange change, Runnable save) {
        prepareActorChange(); Objects.requireNonNull(save);
        if (player.level() != level || !change.holder().equals(player.getUUID().toString())) throw new IllegalArgumentException("Actor belongs to another player or dimension");
        enrollPlayer(player);
        var transition = change.apply(view(), program);
        transferringActor = true;
        try { save.run(); actorOwnerIds.add(player.getUUID()); }
        catch (RuntimeException error) { failed(error, List.of()); throw error; }
        finally { transferringActor = false; }
        try {
            session.observe(nowMicros(), transition.emitted(), new ActorChange.Commit(change));
            refreshPlayers(); refreshProjections();
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    private void validatePendingActors() {
        for (var player : level.players()) if (pendingActorValidation.contains(player.getUUID()) && !player.isRemoved()) {
            var saved = Optional.ofNullable(view().actors().get(player.getUUID().toString()));
            if (!saved.equals(PlayerActor.get(player).projection(program, player.getUUID().toString())))
                throw new IllegalStateException("Restored actor differs from joining player configuration: " + player.getUUID());
            pendingActorValidation.remove(player.getUUID());
        }
    }
    private void refreshActors() {
        if (refreshingActors || session.running() || failure.isPresent()) return;
        refreshingActors = true;
        try {
            validatePendingActors();
            for (var player : List.copyOf(level.players())) {
                if (player.isRemoved() || player.level() != level || !PlayerActor.get(player).configured() && !actorOwnerIds.contains(player.getUUID())) continue;
                String holder = player.getUUID().toString();
                var next = PlayerActor.get(player).projection(program, holder);
                var before = Optional.ofNullable(view().actors().get(holder));
                actorOwnerIds.add(player.getUUID());
                if (!before.equals(next)) actor(new ActorChange(holder, before, next));
            }
            // Absent/dead owners retain their last configuration, so accepted effects keep standing rules.
            // Their Buffs and owned work still follow explicitly declared content lifecycles.
        } finally { refreshingActors = false; }
    }
    private void refreshPlayers() { refreshActors(); refreshEquipment(); refreshAbilities(); }
    private void enrollPlayers() {
        if(resourceHost!=null)resourceHost.enrollPlayers(this,level,List.copyOf(level.players()).stream()
                .filter(player->!player.isRemoved()&&player.level()==level&&level.getEntity(player.getUUID())==player).toList());
    }
    private void enrollPlayer(ServerPlayer player) {
        if(resourceHost!=null)resourceHost.enrollPlayers(this,level,List.of(player));
    }
    void prepareAbilityChange() {
        thread(); prepare();
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Ability selection requires a healthy idle runtime");
    }
    void commitPlayerAbilities(ServerPlayer player, AbilityChange change, Runnable save) {
        prepareAbilityChange(); Objects.requireNonNull(save);
        if (player.level() != level || !change.holder().equals(player.getUUID().toString())) throw new IllegalArgumentException("Abilities belong to another player or dimension");
        enrollPlayer(player);
        var transition = abilityChange(change);var prepared=preparedPublication(transition);
        transferringAbilities = true;
        try { save.run(); abilityOwnerIds.add(player.getUUID()); }
        catch (RuntimeException error) { failed(error, List.of()); throw error; }
        finally { transferringAbilities = false; }
        invalidateAbilityInput(change);
        try {
            publish(transition,new AbilityChange.Commit(change),prepared);
            refreshPlayers(); refreshProjections(); reconcileAbilityInputs();
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    private void invalidateAbilityInput(AbilityChange change) {
        abilityInputs.keySet().removeIf(key -> key.holder().toString().equals(change.holder())
                && !Objects.equals(change.before().slots().get(key.slot()), change.after().slots().get(key.slot())));
    }
    private void validatePendingAbilities() {
        for (var player : level.players()) if (pendingAbilityValidation.contains(player.getUUID()) && !player.isRemoved()) {
            var saved = view().abilities().getOrDefault(player.getUUID().toString(), AbilityLoadout.EMPTY);
            if (!saved.equals(PlayerAbilities.get(player).projection(program)))
                throw new IllegalStateException("Restored abilities differ from joining player selection: " + player.getUUID());
            pendingAbilityValidation.remove(player.getUUID());
        }
    }
    private void refreshAbilities() {
        if (refreshingAbilities || session.running() || failure.isPresent()) return;
        refreshingAbilities = true;
        try {
            validatePendingAbilities();
            for (var player : List.copyOf(level.players())) {
                if (player.isRemoved() || player.level() != level || !PlayerAbilities.get(player).configured() && !abilityOwnerIds.contains(player.getUUID())) continue;
                String holder = player.getUUID().toString();
                var next = PlayerAbilities.get(player).projection(program);
                var before = view().abilities().getOrDefault(holder, AbilityLoadout.EMPTY);
                abilityOwnerIds.add(player.getUUID());
                if (!before.equals(next)) abilities(new AbilityChange(holder, before, next));
            }
        } finally { refreshingAbilities = false; }
    }
    /** Trusted selection boundary. Player unlocks and subclass compatibility belong to the host. */
    public void abilities(com.imdomestic.chorus.effect.ability.AbilityChange change) {
        thread(); prepare(); abilityChange(change); start(change.signal());
    }
    private boolean inputEligible(ServerPlayer player) {
        return player.level() == level && !player.isRemoved() && player.isAlive() && !player.isSpectator();
    }
    private com.imdomestic.chorus.effect.EffectEvent abilityInputEvent(ServerPlayer player, long heldMicros, boolean released) {
        String holder = player.getUUID().toString();
        return new com.imdomestic.chorus.effect.EffectEvent(holder, holder, new BuffInstance.Origin(holder, "", "", ""),
                released ? Set.of("chorus:ability_input_release") : Set.of(),
                Map.of("input_hold_time", new com.imdomestic.chorus.stat.Measure(heldMicros / 1_000_000.0, com.imdomestic.chorus.stat.Unit.SECOND)),
                Map.of("on_ground", player.onGround(), "sprinting", player.isSprinting(), "crouching", player.isCrouching()), Map.of());
    }
    private boolean mayHold(ServerPlayer player, String slot, long gesture, long heldMicros) {
        var input = abilityInputEvent(player, heldMicros, false);
        var query = new com.imdomestic.chorus.effect.EffectEvent(input.actor(), input.victim(), input.source(), Set.of("chorus:ability_input_hold"), input.numbers(), input.flags(), input.references());
        localAbilityOwner(player.getUUID().toString());var request=new AbilityUse.Request(player.getUUID().toString(),slot,"input/"+gesture,query);
        return query(view(),s->program.abilityInputAllowed(s,request),v->program.abilityInputAllowed(v,request));
    }
    private void reconcileAbilityInputs() {
        if (failure.isPresent()) { abilityInputs.clear(); return; }
        if (session.running()) return;
        abilityInputs.entrySet().removeIf(entry -> {
            var held = entry.getValue(); var player = held.player();
            if (!inputEligible(player)) return true;
            var selected = program.selectedAbility(view(), entry.getKey().holder().toString(), entry.getKey().slot());
            return selected.isEmpty() || !selected.orElseThrow().id().equals(held.base()) || !mayHold(player, entry.getKey().slot(), held.gesture(), nowMicros() - held.startedMicros());
        });
    }
    /** Pure identity check for a prepared native input handoff; never advances time or grants input. */
    public boolean hasAbilityInput(ServerPlayer player,String slot,long gesture) {
        ownerThread();var input=abilityInputs.get(new InputKey(player.getUUID(),slot));
        return input!=null&&input.player()==player&&input.gesture()==gesture&&inputEligible(player);
    }
    /** Read-only server observation for input feedback. Preparing also discards invalid holds. */
    public java.util.OptionalLong heldAbilityInput(ServerPlayer player, String slot) {
        thread(); prepare(); var held = abilityInputs.get(new InputKey(player.getUUID(), slot));
        return held == null || held.player() != player ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(nowMicros() - held.startedMicros());
    }
    /** Release consumes the matching gesture before resolving, paying for, or executing its cast. */
    public AbilityInput.Receipt abilityInput(ServerPlayer player, String slot, AbilityInput.Edge edge, long gesture) {
        thread(); prepare(); AbilityDefinition.id(slot); Objects.requireNonNull(edge);
        if (gesture <= 0) throw new IllegalArgumentException("Missing ability gesture");
        if (!inputEligible(player)) throw new IllegalArgumentException("Player cannot use abilities here");
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Ability input requires a healthy idle runtime");
        var key = new InputKey(player.getUUID(), slot); var held = abilityInputs.get(key);
        if (edge == AbilityInput.Edge.PRESS) {
            if (held != null) return AbilityInput.Receipt.input(AbilityInput.Outcome.ALREADY_HELD, nowMicros() - held.startedMicros());
            var base = program.selectedAbility(view(), player.getUUID().toString(), slot);
            if (base.isEmpty()) return AbilityInput.Receipt.input(AbilityInput.Outcome.EMPTY_SLOT, 0);
            if (!mayHold(player, slot, gesture, 0)) return AbilityInput.Receipt.input(AbilityInput.Outcome.RESTRICTED, 0);
            abilityInputs.put(key, new HeldInput(player, gesture, base.orElseThrow().id(), nowMicros()));
            return AbilityInput.Receipt.input(AbilityInput.Outcome.PRESSED, 0);
        }
        if (held == null) return AbilityInput.Receipt.input(AbilityInput.Outcome.NO_PRESS, 0);
        if (held.player() != player || held.gesture() != gesture) return AbilityInput.Receipt.input(AbilityInput.Outcome.STALE, 0);
        abilityInputs.remove(key); long elapsed = Math.subtractExact(nowMicros(), held.startedMicros());
        if (edge == AbilityInput.Edge.CANCEL) return AbilityInput.Receipt.input(AbilityInput.Outcome.CANCELLED, elapsed);
        return new AbilityInput.Receipt(AbilityInput.Outcome.RELEASED, elapsed, Optional.of(useAbility(player, slot, elapsed, true)));
    }
    public static void cancelAbilityInputs(net.minecraft.server.network.ServerGamePacketListenerImpl connection) {
        for (var runtime : LIVE.values()) if (runtime.level.getServer() == connection.player.level().getServer()) {
            runtime.ownerThread(); // Disconnection retires non-persistent input handles, including during pinned shutdown.
            runtime.abilityInputs.entrySet().removeIf(e -> e.getValue().player().connection == connection);
        }
    }
    /** Self-owned intent only. Installed content selects and authorizes the target on the server. */
    public boolean requestFinisher(ServerPlayer player) {
        thread(); prepare();
        if (!inputEligible(player)) throw new IllegalArgumentException("Player cannot request a finisher here");
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Finisher input requires a healthy idle runtime");
        String holder = player.getUUID().toString();
        if (com.imdomestic.chorus.effect.finisher.Finisher.busy(view(), holder)) return false;
        var event = new com.imdomestic.chorus.effect.EffectEvent(holder, holder, new BuffInstance.Origin(holder, "", "", ""),
                Set.of("chorus:finisher_input"), Map.of(), Map.of("on_ground", player.onGround(), "sprinting", player.isSprinting(), "crouching", player.isCrouching()),
                Map.of("request_id", java.util.UUID.randomUUID().toString()));
        start(new RuleEngine.Signal("chorus:finisher_requested", event));
        return true;
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
        thread(); abilityInputs.remove(new InputKey(player.getUUID(), slot));
        return useAbility(player, slot, 0, false);
    }
    private com.imdomestic.chorus.effect.ability.AbilityUse.Receipt useAbility(ServerPlayer player, String slot, long heldMicros, boolean released) {
        thread(); prepare();
        if (player.level() != level || player.isRemoved() || !player.isAlive() || player.isSpectator()) throw new IllegalArgumentException("Player cannot use abilities here");
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Ability use requires a healthy idle runtime");
        String holder = player.getUUID().toString();
        var input = abilityInputEvent(player, heldMicros, released);
        var request = new com.imdomestic.chorus.effect.ability.AbilityUse.Request(holder, slot, java.util.UUID.randomUUID().toString(), input);
        localAbilityOwner(holder);
        var before = view();var resolved=query(before,s->program.useAbility(s,request),v->program.useAbility(v,request,"native/"+level.dimension().identifier()+"/"+request.cast()));
        var receipt = (com.imdomestic.chorus.effect.ability.AbilityUse.Receipt) resolved.result();
        if (receipt.outcome() != com.imdomestic.chorus.effect.ability.AbilityUse.Outcome.ACCEPTED) return receipt;
        try {
            session.observe(nowMicros(), resolved.emitted(), new com.imdomestic.chorus.effect.ability.AbilityUse.Commit(before, resolved.state()));
            refreshPlayers(); refreshProjections(); return receipt;
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
        var before = view();var request=new com.imdomestic.chorus.effect.weapon.WeaponReload.Request(holder, java.util.UUID.randomUUID().toString());var resolved=query(before,s->program.reload(s,request),v->program.reload(v,request));
        var receipt = (com.imdomestic.chorus.effect.weapon.WeaponReload.Receipt) resolved.result();
        if (receipt.outcome() != com.imdomestic.chorus.effect.weapon.WeaponReload.Outcome.ACCEPTED) return receipt;
        try {
            session.observe(nowMicros(), resolved.emitted(), new com.imdomestic.chorus.effect.weapon.WeaponReload.Commit(before, resolved.state()));
            refreshPlayers(); refreshProjections(); return receipt;
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    /** Timed selection uses the real container and consumes the supplied revision on acceptance. */
    public boolean switchWeapon(ServerPlayer player, Optional<String> slot, long expectedRevision) {
        thread(); prepare();
        if (player.level() != level || player.isRemoved() || !player.isAlive() || player.isSpectator()) throw new IllegalArgumentException("Player cannot switch weapons here");
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Selection requires a healthy idle runtime");
        String holder = player.getUUID().toString(); var equipment = PlayerEquipment.get(player);
        if (equipment.revision() != expectedRevision) throw new IllegalStateException("Stale equipment revision");
        if (!equipment.projection(program.equipment()).equals(view().equipment().getOrDefault(holder, com.imdomestic.chorus.effect.equipment.Loadout.EMPTY)))
            throw new IllegalStateException("Selection differs from physical ownership");
        if (!query(view(),s->program.usesHandling(s,holder,slot),v->program.usesHandling(v,holder,slot))) return false;
        trackEquipment(player);
        var before = view();var request=new com.imdomestic.chorus.effect.weapon.WeaponHandling.Request(holder, java.util.UUID.randomUUID().toString(), slot);var resolved=query(before,s->program.switchWeapon(s,request),v->program.switchWeapon(v,request));
        if (((com.imdomestic.chorus.effect.weapon.WeaponHandling.Receipt) resolved.result()).outcome() == com.imdomestic.chorus.effect.weapon.WeaponHandling.Outcome.UNCHANGED) return true;
        equipment.acceptHandling(player, expectedRevision);
        try {
            session.observe(nowMicros(), resolved.emitted(), new com.imdomestic.chorus.effect.weapon.WeaponHandling.Commit(before, resolved.state()));
            refreshPlayers(); refreshProjections(); return true;
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    private com.imdomestic.chorus.effect.weapon.WeaponHandling.Applied applyHandling(com.imdomestic.chorus.effect.weapon.WeaponHandling.Apply command) {
        ServerPlayer player;
        try { player = equipmentOwners.get(java.util.UUID.fromString(command.plan().holder())); }
        catch (IllegalArgumentException invalid) { player = null; }
        boolean eligible = player != null && !player.isRemoved() && player.level() == level && player.isAlive() && !player.isSpectator()
                && command.plan().equals(view().handling().get(command.plan().holder()));
        boolean applied = eligible && PlayerEquipment.get(player).applyHandling(player, command.change(), program.equipment());
        return new com.imdomestic.chorus.effect.weapon.WeaponHandling.Applied(command, applied);
    }
    /** An aim edge carries only intent; duration, weapon and holder come from server-owned state. */
    public com.imdomestic.chorus.effect.weapon.WeaponAim.Receipt aim(ServerPlayer player, boolean pressed) {
        thread(); prepare();
        if (player.level() != level || player.isRemoved() || !player.isAlive() || player.isSpectator()) throw new IllegalArgumentException("Player cannot aim here");
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Aim requires a healthy idle runtime");
        String holder = player.getUUID().toString();
        if (!PlayerEquipment.get(player).projection(program.equipment()).equals(view().equipment().getOrDefault(holder, com.imdomestic.chorus.effect.equipment.Loadout.EMPTY)))
            throw new IllegalStateException("Aim differs from physical ownership");
        trackEquipment(player);
        var before = view();var request=new com.imdomestic.chorus.effect.weapon.WeaponAim.Request(holder, java.util.UUID.randomUUID().toString(), pressed);var resolved=query(before,s->program.aim(s,request),v->program.aim(v,request));
        var receipt = (com.imdomestic.chorus.effect.weapon.WeaponAim.Receipt) resolved.result();
        if (receipt.outcome() != com.imdomestic.chorus.effect.weapon.WeaponAim.Outcome.ACCEPTED) return receipt;
        try {
            session.observe(nowMicros(), resolved.emitted(), new com.imdomestic.chorus.effect.weapon.WeaponAim.Commit(before, resolved.state()));
            refreshPlayers(); refreshProjections(); return receipt;
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    /** Query the ongoing gate against the actual current aim, without accepting a new edge. */
    public Optional<ActionGate.Decision> aimContinuation(ServerPlayer player) {
        thread(); prepare();
        if (!inputEligible(player) || failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Aim observation requires an eligible idle runtime");
        var plan = view().aiming().get(player.getUUID().toString());
        if (plan == null || !plan.held()) return Optional.empty();
        var query = new EffectEvent(plan.holder(), plan.gear().instance(), plan.origin(), plan.origin().tags(), Map.of(),
                Map.of("aim_held", true, "aim_ready", plan.phase() == com.imdomestic.chorus.effect.weapon.WeaponAim.Phase.AIMED),
                Map.of("weapon", plan.gear().instance(), "item", plan.gear().definition(), "aim", plan.token(), "aim_phase", plan.phase().name().toLowerCase(java.util.Locale.ROOT)));
        return Optional.of(gate(ActionGate.Kind.WEAPON_AIM, ActionGate.Phase.CONTINUE, query));
    }
    /** Host cancellation may address a departed/dead holder; no physical input or exit animation is required. */
    public void cancelAim(String holder, String reason) {
        thread(); prepare();
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Aim cancellation requires a healthy idle runtime");
        var before = view();var ended=query(before,s->program.cancelAim(s,holder,reason),v->program.cancelAim(v,holder,reason));
        if (ended.state().equals(before)) return;
        try {
            session.observe(nowMicros(), ended.emitted(), new com.imdomestic.chorus.effect.weapon.WeaponAim.Commit(before, ended.state()));
            refreshPlayers(); refreshProjections();
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    private com.imdomestic.chorus.effect.weapon.WeaponAim.Verified verifyAim(com.imdomestic.chorus.effect.weapon.WeaponAim.Verify query) {
        ServerPlayer player;
        try { player = equipmentOwners.get(java.util.UUID.fromString(query.plan().holder())); }
        catch (IllegalArgumentException invalid) { player = null; }
        boolean allowed = player != null && !player.isRemoved() && player.level() == level && player.isAlive() && !player.isSpectator()
                && query.plan().equals(view().aiming().get(query.plan().holder()))
                && query.plan().matches(PlayerEquipment.get(player).projection(program.equipment()));
        return new com.imdomestic.chorus.effect.weapon.WeaponAim.Verified(query, allowed);
    }
    /** Debug/host input convenience. Network adapters must pair their own gesture with the returned token. */
    public com.imdomestic.chorus.effect.weapon.WeaponCharge.Receipt charge(ServerPlayer player, com.imdomestic.chorus.effect.weapon.WeaponCharge.Edge edge) {
        thread();
        var plan = view().charging().get(player.getUUID().toString());
        String token = edge == com.imdomestic.chorus.effect.weapon.WeaponCharge.Edge.PRESS || plan == null ? java.util.UUID.randomUUID().toString() : plan.token();
        return charge(player, token, edge);
    }
    /** Self-owned input; timing comes from the server and a release must match its accepted press. */
    public com.imdomestic.chorus.effect.weapon.WeaponCharge.Receipt charge(ServerPlayer player, String token, com.imdomestic.chorus.effect.weapon.WeaponCharge.Edge edge) {
        return charge(player, token, edge, () -> true);
    }
    /** Ephemeral host authority is required before autonomous firing; it is never restored from a checkpoint. */
    public com.imdomestic.chorus.effect.weapon.WeaponCharge.Receipt charge(ServerPlayer player, String token, com.imdomestic.chorus.effect.weapon.WeaponCharge.Edge edge, java.util.function.BooleanSupplier held) {
        Objects.requireNonNull(held);
        thread();
        var pending = view().charging().get(player.getUUID().toString());
        if (edge != com.imdomestic.chorus.effect.weapon.WeaponCharge.Edge.PRESS && pending != null
                && pending.token().equals(token) && inputEligible(player) && matchesChargeHolder(pending, player)
                && (edge == com.imdomestic.chorus.effect.weapon.WeaponCharge.Edge.CANCEL || pending.trigger() == com.imdomestic.chorus.effect.weapon.WeaponCharge.Trigger.FULL)) {
            cancelCharge(pending.holder(), token, "input_" + edge.name().toLowerCase(java.util.Locale.ROOT));
            return new com.imdomestic.chorus.effect.weapon.WeaponCharge.Receipt(com.imdomestic.chorus.effect.weapon.WeaponCharge.Outcome.CANCELLED, Optional.of(pending), Optional.empty(), Optional.empty());
        }
        prepare();
        if (!inputEligible(player)) throw new IllegalArgumentException("Player cannot charge here");
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Charge requires a healthy idle runtime");
        String holder = player.getUUID().toString();
        if (!PlayerEquipment.get(player).projection(program.equipment()).equals(view().equipment().getOrDefault(holder, com.imdomestic.chorus.effect.equipment.Loadout.EMPTY)))
            throw new IllegalStateException("Charge differs from physical ownership");
        trackEquipment(player);
        var before = view();var request=new com.imdomestic.chorus.effect.weapon.WeaponCharge.Request(holder, token, edge, Optional.of(chargeHolder(player)));var resolved=query(before,s->program.charge(s,request),v->program.charge(v,request));
        var receipt = (com.imdomestic.chorus.effect.weapon.WeaponCharge.Receipt) resolved.result();
        if (resolved.state().equals(before) && resolved.emitted().isEmpty()) return receipt;
        if (receipt.outcome() == com.imdomestic.chorus.effect.weapon.WeaponCharge.Outcome.ACCEPTED) chargeInputs.put(holder, new ChargeInput(player, player.connection, token, held));
        else if (receipt.plan().isPresent()) revokeChargeInput(holder, Optional.of(token));
        try {
            session.observe(nowMicros(), resolved.emitted(), new com.imdomestic.chorus.effect.weapon.WeaponCharge.Commit(before, resolved.state()));
            refreshPlayers(); refreshProjections(); return receipt;
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    public void cancelCharge(String holder, String reason) {
        cancelCharge(holder, Optional.empty(), reason);
    }
    /** A connection owns only its accepted token, including after prepare has settled due reactions. */
    public void cancelCharge(String holder, String token, String reason) {
        cancelCharge(holder, Optional.of(token), reason);
    }
    private void cancelCharge(String holder, Optional<String> token, String reason) {
        thread(); revokeChargeInput(holder, token); prepare();
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Charge cancellation requires a healthy idle runtime");
        if (token.isPresent() && (view().charging().get(holder) == null || !view().charging().get(holder).token().equals(token.orElseThrow()))) return;
        try { commitChargeCancellation(holder, reason); refreshPlayers(); refreshProjections(); }
        catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    /** Connection teardown also retires trusted command gestures, without replaying failed runtime work. */
    public static void cancelChargeInputs(net.minecraft.server.network.ServerGamePacketListenerImpl connection) {
        for (var runtime : List.copyOf(LIVE.values())) if (runtime.level.getServer() == connection.player.level().getServer() && runtime.failure().isEmpty()) {
            runtime.ownerThread();
            for (var player : List.copyOf(runtime.equipmentOwners.values())) if (player.connection == connection
                    && runtime.view().charging().containsKey(player.getUUID().toString())) runtime.cancelCharge(player.getUUID().toString(), "input_disconnected");
        }
    }
    /** Called before pinning a clean-stop checkpoint; persistent domain plans do not prove a live physical hold. */
    public static void quiesceCharges(net.minecraft.server.MinecraftServer server) {
        // Revoke every pending command before settling any runtime's due work.
        for (var runtime : List.copyOf(LIVE.values())) if (runtime.level.getServer() == server) { runtime.ownerThread(); runtime.chargeInputs.clear(); }
        for (var runtime : List.copyOf(LIVE.values())) if (runtime.level.getServer() == server && runtime.failure().isEmpty()) {
            runtime.ownerThread();
            for (var holder : List.copyOf(runtime.view().charging().keySet())) runtime.cancelCharge(holder, "input_server_stopping");
        }
    }
    private void commitChargeCancellation(String holder, String reason) {
        revokeChargeInput(holder, Optional.empty());
        var before = view();var ended=query(before,s->program.cancelCharge(s,holder,reason),v->program.cancelCharge(v,holder,reason));
        if (!ended.state().equals(before)) session.observe(nowMicros(), ended.emitted(), new com.imdomestic.chorus.effect.weapon.WeaponCharge.Commit(before, ended.state()));
    }
    private com.imdomestic.chorus.effect.weapon.WeaponCharge.Verified verifyCharge(com.imdomestic.chorus.effect.weapon.WeaponCharge.Verify query) {
        ServerPlayer player;
        try { player = equipmentOwners.get(java.util.UUID.fromString(query.plan().holder())); }
        catch (IllegalArgumentException invalid) { player = null; }
        boolean allowed = player != null && inputEligible(player) && matchesChargeHolder(query.plan(), player) && chargeInputAllowed(query.plan(), player)
                && query.plan().equals(view().charging().get(query.plan().holder()))
                && query.plan().matches(PlayerEquipment.get(player).projection(program.equipment()));
        return new com.imdomestic.chorus.effect.weapon.WeaponCharge.Verified(query, allowed);
    }
    private void revokeChargeInput(String holder, Optional<String> token) {
        var input = chargeInputs.get(holder);
        if (input != null && (token.isEmpty() || token.orElseThrow().equals(input.token()))) chargeInputs.remove(holder);
    }
    private boolean chargeInputAllowed(com.imdomestic.chorus.effect.weapon.WeaponCharge.Plan plan, ServerPlayer player) {
        if (plan.trigger() == com.imdomestic.chorus.effect.weapon.WeaponCharge.Trigger.RELEASE) return true;
        var input = chargeInputs.get(plan.holder());
        return input != null && input.player() == player && input.token().equals(plan.token())
                && input.connection() != null && player.connection == input.connection() && input.connection().player == player && input.held().getAsBoolean();
    }
    private com.imdomestic.chorus.effect.target.EntityAnchor.Reference chargeHolder(ServerPlayer player) {
        return new com.imdomestic.chorus.effect.target.EntityAnchor.Reference(player.getUUID().toString(), MinecraftEntityLife.get(player).id(), level.dimension().identifier().toString());
    }
    private boolean matchesChargeHolder(com.imdomestic.chorus.effect.weapon.WeaponCharge.Plan plan, ServerPlayer player) {
        return plan.holderReference().filter(chargeHolder(player)::equals).isPresent();
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
        var before = view();var request=new com.imdomestic.chorus.effect.weapon.WeaponFire.Request(holder,java.util.UUID.randomUUID().toString());
        var resolved=query(before,s->program.fire(s,request),v->program.fire(v,request));
        var receipt = (com.imdomestic.chorus.effect.weapon.WeaponFire.Receipt) resolved.result();
        if (receipt.outcome() != com.imdomestic.chorus.effect.weapon.WeaponFire.Outcome.ACCEPTED) return receipt;
        try {
            session.observe(nowMicros(), resolved.emitted(), new com.imdomestic.chorus.effect.weapon.WeaponFire.Commit(before, resolved.state()));
            refreshPlayers(); refreshProjections(); return receipt;
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
        thread(); prepare(); equipmentChange(change); start(change.signal());
    }
    void prepareEquipmentChange() {
        thread(); prepare();
        if (failure.isPresent() || session.running() || !state().idle()) throw new IllegalStateException("Equipment requires a healthy idle runtime");
    }
    /** Server container boundary: preflight all pure work, transfer ownership, then reconcile before any reaction. */
    public void commitEquipment(com.imdomestic.chorus.effect.equipment.EquipmentChange change, Runnable transferItems) {
        prepareEquipmentChange();
        var transition = equipmentChange(change);var prepared=preparedPublication(transition);
        transferringEquipment = true;
        try { transferItems.run(); }
        catch (RuntimeException error) { failed(error, List.of()); throw error; }
        finally { transferringEquipment = false; }
        try {
            publish(transition,new com.imdomestic.chorus.effect.equipment.EquipmentChange.Commit(change),prepared);
            refreshPlayers(); refreshProjections();
        } catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    public void trackEquipment(ServerPlayer player) {
        thread(); if (player.level() != level) throw new IllegalArgumentException("Equipment owner is in another level");
        enrollPlayer(player);
        equipmentOwners.put(player.getUUID(), player);
        equipmentOwnerIds.add(player.getUUID());
    }
    private void refreshEquipment() {
        if (refreshingEquipment || session.running() || failure.isPresent()) return;
        refreshingEquipment = true;
        try {
            for (var player : level.players()) if (!PlayerEquipment.get(player).isEmpty() || !PlayerEquipment.get(player).grants().isEmpty() || equipmentOwnerIds.contains(player.getUUID())) {
                equipmentOwners.put(player.getUUID(), player); equipmentOwnerIds.add(player.getUUID());
            }
            for (var entry : List.copyOf(equipmentOwners.entrySet())) {
                var player = entry.getValue(); boolean present = !player.isRemoved() && player.level() == level;
                var next = present && player.isAlive() ? PlayerEquipment.get(player).projection(program.equipment()) : com.imdomestic.chorus.effect.equipment.Loadout.EMPTY;
                String holder = entry.getKey().toString();
                var before = view().equipment().getOrDefault(holder, com.imdomestic.chorus.effect.equipment.Loadout.EMPTY);
                if (!before.equals(next)) equip(new com.imdomestic.chorus.effect.equipment.EquipmentChange(holder, before, next));
                if (player.isSpectator() && view().aiming().containsKey(holder)) {
                    var aimBefore = view();var ended=query(aimBefore,s->program.cancelAim(s,holder,"spectator"),v->program.cancelAim(v,holder,"spectator"));
                    session.observe(nowMicros(), ended.emitted(), new com.imdomestic.chorus.effect.weapon.WeaponAim.Commit(aimBefore, ended.state()));
                }
                var charge = view().charging().get(holder);
                if (charge != null) {
                    if (!inputEligible(player) || !matchesChargeHolder(charge, player)) commitChargeCancellation(holder, "holder_unavailable");
                    else if (!chargeInputAllowed(charge, player)) commitChargeCancellation(holder, "input_unavailable");
                    else if (!gate(ActionGate.Kind.WEAPON_FIRE, ActionGate.Phase.CONTINUE,
                            charge.event(view().buffs().timeMicros(), true)).allowed()) commitChargeCancellation(holder, "restricted");
                }
                // An empty container can acquire and lose an item within one reaction boundary (e.g. attach kills its owner).
                // Keep live owners tracked until they leave, so the final empty projection still reconciles that boundary.
                if (!present) equipmentOwners.remove(entry.getKey());
            }
            chargeInputs.entrySet().removeIf(entry -> { var charge = view().charging().get(entry.getKey()); return charge == null || !charge.token().equals(entry.getValue().token()); });
        } finally { refreshingEquipment = false; }
    }
    public Optional<Failure> failure() { return failure; }
    private void ownerThread() {
        if (closed || transferringEquipment || transferringActor || transferringAbilities || !level.getServer().isSameThread()) throw new IllegalStateException("Runtime is closed, transferring player state, or accessed off its server thread");
    }
    private void thread() {
        ownerThread();
        if(resourceHost!=null)resourceHost.enteringRuntime();
        MinecraftCheckpointBoundary.enteringRuntime(level.getServer(), this);
    }
    public long nowMicros() {
        return Math.addExact(originMicros, Math.multiplyExact(Math.subtractExact(level.getGameTime(), originTick), 50_000));
    }
    public void start(RuleEngine.Signal signal) {
        thread();
        if (failure.isPresent()) throw new IllegalStateException("Failed runtime requires explicit recovery");
        if (signal.payload() instanceof AbilityChange change) {
            abilityChange(change); // Invalid/stale requests do not erase a valid held input.
            invalidateAbilityInput(change);
        }
        try {
            validatePendingActors(); validatePendingAbilities(); enrollPlayers(); refreshActors(); refreshAbilities();
            settleNativeLifecycle();
            session.start(nowMicros(), signal);
            refreshPlayers(); refreshProjections(); reconcileAbilityInputs();
        }
        catch (RuntimeException error) { failed(error, List.of()); throw error; }
    }
    @Override public void prepare() {
        thread();
        if (failure.isPresent() || session.running()) return;
        try { validatePendingActors(); validatePendingAbilities(); enrollPlayers(); settleNativeLifecycle(); if (nowMicros() != state().engine().timeMicros()) session.observe(nowMicros(), List.of()); refreshPlayers(); refreshProjections(); reconcileAbilityInputs(); }
        catch (RuntimeException error) { failed(error, List.of()); }
    }
    /** Settle older native observations before advancing to a later fuse deadline. */
    private void settleNativeLifecycle() {
        var pending = takeNativeLifecycleFacts();
        int begin = 0;
        while (begin < pending.size()) {
            var first = pending.get(begin);
            long at = nativeFactTime(first, state().engine().timeMicros());
            at = Math.max(at, state().engine().timeMicros());
            int end = begin + 1;
            while (end < pending.size()) {
                var next = pending.get(end);
                long nextAt = nativeFactTime(next, at);
                if (nextAt > at) break;
                end++;
            }
            var facts = pending.subList(begin, end);
            settlingLifecycleFacts = pending.subList(begin, pending.size());
            try { session.observe(at, facts, com.imdomestic.chorus.effect.target.EntityAttachments.commit(RuleEngine.Empty.INSTANCE, facts)); }
            catch (RuntimeException error) { failed(error, List.of()); throw error; }
            finally { settlingLifecycleFacts = List.of(); }
            begin = end;
        }
    }
    private static long nativeFactTime(RuleEngine.Signal fact, long fallback) {
        if (fact.payload() instanceof com.imdomestic.chorus.effect.motion.GroundContact.Landed landed) return landed.observation().timeMicros();
        return com.imdomestic.chorus.effect.target.EntityAttachments.lifecycle(fact)
                ? com.imdomestic.chorus.effect.target.EntityAttachments.time(fact) : fallback;
    }
    @Override public DamageCommand describe(LivingEntity target, DamageSource source, float amount) {
        thread();
        describingDamage++;
        try {
            var command = Objects.requireNonNull(sources.describe(target, source, amount));
            return failure.isPresent() ? command : program.prepareReactions(view(), command);
        }
        catch (RuntimeException error) { failed(error, List.of()); return nativeSource(target, source, amount); }
        finally { describingDamage--; }
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
            var state=attackView(command,id);var prepared=program.prepareDamage(state,command,resources(state));
            reservations.put(id, new ConsumptionReservations.Claim(id, prepared)); activeDamage.push(id); return prepared;
        } catch (RuntimeException error) { failed(error, List.of()); return command; }
    }
    @Override public DamageCommand revise(String id, DamageCommand command) {
        thread();
        if (failure.isPresent()) return command;
        try {
            if (!id.equals(activeDamage.peek())) throw new IllegalStateException("Native damage source revision is not current");
            var state=attackView(command,id);var prepared=program.prepareDamage(state,command,resources(state));
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
    @Override public void confirmedDeath(String deathId, LivingEntity target) {
        if (closed || MinecraftCheckpointLifecycle.deferStop(level.getServer())) return;
        ownerThread();
        var reference = new com.imdomestic.chorus.effect.target.EntityAnchor.Reference(target.getUUID().toString(),
                MinecraftEntityLife.get(target).id(), level.dimension().identifier().toString());
        var positions = new java.util.HashMap<com.imdomestic.chorus.effect.target.PositionQuery, Optional<com.imdomestic.chorus.effect.target.WorldPosition>>();
        for (var anchor : com.imdomestic.chorus.effect.target.TargetQuery.Anchor.values())
            positions.put(new com.imdomestic.chorus.effect.target.PositionQuery(reference.target(), anchor), Optional.of(MinecraftWorldActions.observePosition(target, anchor)));
        var observation = new EntityObservation(nowMicros(), Map.of(reference.target(), Optional.of(MinecraftWorldActions.observeEntity(target))), positions);
        recordNativeLifecycle(new com.imdomestic.chorus.effect.target.EntityLifecycle.Died(reference, deathId, observation).signal());
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
            var resources=resources(view);var plan=resources.isPresent()?program.shields(resources.orElseThrow(),command,amount,basis,attackView(command)):program.shields(view,command,amount,basis,attackView(command));
            // The immutable shadow is visible to nested native hits before the outer receipt reaches the interpreter.
            combatView = plan.commit().apply(view); shieldWrites.addAll(plan.commit().writes()); return plan;
        } catch (RuntimeException error) { failed(error, List.of()); return ShieldDamage.plan(amount, List.of()); }
    }
    private EffectState view() { return combatView == null ? state().engine().domain() : combatView; }
    @Override public Optional<CalculationProfile.Result> outgoing(DamageCommand command, double amount) {
        thread();
        if (failure.isPresent()) return Optional.empty();
        try { return query(attackView(command),s->program.outgoing(s,command,amount),v->program.outgoing(v,command,amount)); }
        catch (RuntimeException error) { failed(error, List.of()); return Optional.empty(); }
    }
    @Override public Optional<CalculationProfile.Result> defense(DamageCommand command, double amount) {
        thread();
        if (failure.isPresent()) return Optional.empty();
        try { return query(view(),s->program.defense(s,command,amount),v->program.defense(v,command,amount)); }
        catch (RuntimeException error) { failed(error, List.of()); return Optional.empty(); }
    }
    private CombatCommit combatCommit() { return new CombatCommit(state().engine().domain(), view()); }
    @Override public Optional<CalculationProfile.Result> health(DamageCommand command, double amount) {
        thread();
        if (failure.isPresent()) return Optional.empty();
        // Let the outer native boundary settle prior shield writes before reporting an unknown HP result.
        return query(view(),s->program.health(s,command,amount),v->program.health(v,command,amount));
    }
    private CombatCommit takeWrites() {
        var result = combatCommit(); shieldWrites.clear(); combatView = null; completedDamage.clear(); return result;
    }
    @Override public void committed(List<DamageCapture.Observed> observations) {
        thread();
        var signals = new ArrayList<>(combatFacts); combatFacts.clear();
        if (failure.isPresent()) { countUnprocessed(observations.stream().mapToLong(value -> DamageFacts.from(value.command(), value.receipt()).size()).sum()); return; }
        if (operationFacts != null) { operationFacts.addAll(signals); return; }
        signals.addAll(takeNativeLifecycleFacts());
        var shieldCommit = new ShieldDamage.Commit(shieldWrites); var writes = takeWrites();
        if (signals.isEmpty() && !writes.changed()) return;
        try { session.observe(nowMicros(), signals, com.imdomestic.chorus.effect.target.EntityAttachments.commit(writes, signals)); refreshPlayers(); refreshProjections(); }
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
        var knownFacts = new ArrayList<>(facts); knownFacts.addAll(settlingLifecycleFacts); knownFacts.addAll(takeNativeLifecycleFacts());
        failure = Optional.of(new Failure(error.getClass().getSimpleName() + ": " + error.getMessage(), committedBeforeFailure, 0,
                committedShields, committedCombat, List.copyOf(reservations.values()), knownFacts));
        abilityInputs.clear(); chargeInputs.clear();
        abandonProjectiles();
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
        try { runtime.prepare(); runtime.maintainProjectiles(); runtime.start(new RuleEngine.Signal("chorus:tick", RuleEngine.Empty.INSTANCE)); }
        catch (RuntimeException error) { runtime.failed(error, List.of()); }
    }
    public static void unload(ServerLevel level) {
        if (MinecraftCheckpointLifecycle.deferUnload(level)) return;
        var runtime = LIVE.get(level); if (runtime != null) runtime.close();
    }
    public static void stop(MinecraftServer server) {
        if (MinecraftCheckpointLifecycle.deferStop(server)) return;
        MinecraftResourceRuntime.stop(server);
        List.copyOf(LIVE.values()).stream().filter(runtime -> runtime.level.getServer() == server).forEach(MinecraftEffectRuntime::close);
        DamageCapture.clear(server);
    }
    @Override public void close() {
        if (closed) return;
        if(resourceHost!=null&&!resourceHost.closing())throw new IllegalStateException("Close the coordinated native host as a whole");
        thread();
        if (session.running()) throw new IllegalStateException("Cannot detach a running runtime");
        closed = true; equipmentOwners.clear(); equipmentOwnerIds.clear(); actorOwnerIds.clear(); pendingActorValidation.clear(); abilityOwnerIds.clear(); pendingAbilityValidation.clear(); abilityInputs.clear(); chargeInputs.clear(); nativeLifecycleFacts.clear(); LIVE.remove(level, this); DamageCapture.remove(level, this);
        abandonProjectiles();
        var constructs = new ArrayList<EffectConstruct>();
        var pickups = new ArrayList<EffectObject>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof EffectConstruct construct && construct.ownedBy(this)) constructs.add(construct);
            if (entity instanceof EffectObject pickup && pickup.ownedBy(this)) pickups.add(pickup);
        }
        constructs.forEach(EffectConstruct::discard);
        pickups.forEach(EffectObject::discard);
        RuntimeException cleanup=null;
        try { nativeAttributes.close(); } catch(RuntimeException error) { cleanup=error; }
        try { nativeMovement.close(); } catch(RuntimeException error) { if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error); }
        try { nativeHorizontalSpeed.close(); } catch(RuntimeException error) { if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error); }
        if(cleanup!=null)throw cleanup;
    }
}
