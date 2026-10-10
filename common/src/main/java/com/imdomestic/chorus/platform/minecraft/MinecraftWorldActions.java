package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.combat.StatusResult;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import com.imdomestic.chorus.effect.data.Action;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.effect.target.TargetShape;
import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.effect.target.DirectionQuery;
import com.imdomestic.chorus.effect.target.PositionQuery;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Explicit world bindings. Damage-source mapping and status immunity policy are supplied by the host. */
public final class MinecraftWorldActions implements Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> {
    private final ServerLevel level;
    private final Function<String, LivingEntity> entities;
    private final Function<DamageCommand, DamageSource> damageSources;
    private final BiPredicate<LivingEntity, StatusResult.Check> statuses;
    private final Consumer<Action.CueCommand> cues;
    private final String sessionId = UUID.randomUUID().toString();

    public MinecraftWorldActions(ServerLevel level, Function<String, LivingEntity> entities,
            Function<DamageCommand, DamageSource> damageSources, BiPredicate<LivingEntity, StatusResult.Check> statuses,
            Consumer<Action.CueCommand> cues) {
        this.level = Objects.requireNonNull(level); this.entities = Objects.requireNonNull(entities);
        this.damageSources = Objects.requireNonNull(damageSources); this.statuses = Objects.requireNonNull(statuses); this.cues = Objects.requireNonNull(cues);
    }
    @Override public RuleEngine.ActionResult apply(RuleEngine.WorldRequest request) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("World actions require the server thread");
        return switch (request.command()) {
            case DamageCommand damage -> {
                String id = sessionId + "/" + request.id().frame() + "/" + request.id().pc() + "/" + request.id().invocation();
                var target = entities.apply(damage.target());
                if (target == null || target.isRemoved() || target.level() != level) {
                    yield new DamageReceipt(id, DamageReceipt.Outcome.FAILED, 0, 0, 0, Optional.empty(), false);
                }
                yield MinecraftDamageExecutor.executeManaged(id, target, Objects.requireNonNull(damageSources.apply(damage)), damage);
            }
            case HealingCommand healing -> {
                String id = sessionId + "/" + request.id().frame() + "/" + request.id().pc() + "/" + request.id().invocation();
                var target = entities.apply(healing.target());
                if (target == null || target.isRemoved() || target.level() != level) {
                    yield HealingReceipt.unapplied(id, healing, HealingReceipt.Outcome.MISSING);
                }
                yield MinecraftHealingExecutor.execute(id, target, healing);
            }
            case com.imdomestic.chorus.effect.combat.HealthPayment.Command payment -> {
                String id=sessionId+"/"+request.id().frame()+"/"+request.id().pc()+"/"+request.id().invocation();
                var target=entities.apply(payment.target());
                if(target==null||target.isRemoved()||target.level()!=level)yield com.imdomestic.chorus.effect.combat.HealthPayment.Receipt.unavailable(id,payment,com.imdomestic.chorus.effect.combat.HealthPayment.Outcome.MISSING);
                yield MinecraftHealthPaymentExecutor.execute(id,target,payment);
            }
            case StatusResult.Check check -> {
                var target = entities.apply(check.target());
                var decision = target == null || target.isRemoved() || target.level() != level ? StatusResult.Decision.MISSING
                        : !target.isAlive() && !check.allowDead() ? StatusResult.Decision.DEAD
                        : statuses.test(target, check) ? StatusResult.Decision.ALLOWED : StatusResult.Decision.DENIED;
                yield new StatusResult.Checked(check, decision);
            }
            case Action.CueCommand cue -> { cues.accept(cue); yield RuleEngine.Empty.INSTANCE; }
            case com.imdomestic.chorus.effect.motion.Impulse.Command impulse -> MinecraftImpulseExecutor.execute(level, entities.apply(impulse.target()), impulse);
            case com.imdomestic.chorus.effect.motion.Displacement.Command displacement -> MinecraftDisplacementExecutor.execute(level, entities.apply(displacement.target()), displacement);
            case PositionQuery query -> {
                var target = entities.apply(query.target());
                yield new PositionQuery.Result(query, present(target) ? Optional.of(position(target, query.anchor())) : Optional.empty());
            }
            case EntityQuery query -> {
                var target = entities.apply(query.target());
                yield new EntityQuery.Result(query, present(target) ? Optional.of(observeEntity(target)) : Optional.empty());
            }
            case DirectionQuery query -> {
                var entity = entities.apply(query.target());
                var direction = Optional.<WorldDirection>empty();
                if (present(entity)) { var v = entity.getLookAngle(); direction = Optional.of(new WorldDirection(level.dimension().identifier().toString(), v.x, v.y, v.z)); }
                yield new DirectionQuery.Result(query, direction);
            }
            case TargetQuery query -> select(query);
            case com.imdomestic.chorus.effect.projectile.ProjectileFlight.Launch launch -> launch(launch);
            case com.imdomestic.chorus.effect.object.WorldPickup.Spawn spawn -> pickup(spawn);
            case com.imdomestic.chorus.effect.object.WorldConstruct.Spawn spawn -> MinecraftConstructExecutor.spawn(level, spawn);
            default -> throw new IllegalArgumentException("No world executor for " + request.command().getClass().getName());
        };
    }
    /** Also accepts a retained damage target that native death hooks have already removed from lookup. */
    static EntityQuery.View observeEntity(LivingEntity target) {
        return new EntityQuery.View(target.isAlive(), target instanceof net.minecraft.world.entity.player.Player,
                target.getHealth(), target.getMaxHealth(), target.getAbsorptionAmount(), target.entityTags(),
                target.getType().builtInRegistryHolder().tags().map(tag -> tag.location().toString()).collect(java.util.stream.Collectors.toSet()),
                Optional.of(new EntityQuery.Movement(target.onGround(), target.isSprinting(), target.isCrouching(), target.isSwimming(),
                        target.isFallFlying(), target.isPassenger(), target.isSleeping())));
    }
    private com.imdomestic.chorus.effect.projectile.ProjectileFlight.Receipt launch(com.imdomestic.chorus.effect.projectile.ProjectileFlight.Launch launch) {
        var outcome = com.imdomestic.chorus.effect.projectile.ProjectileFlight.Outcome.LAUNCHED;
        var id = Optional.<String>empty();
        if (launch.member().isPresent() && !com.imdomestic.chorus.effect.projectile.ShotGroups.active(
                MinecraftEffectRuntime.installed(level).orElseThrow().state().engine().domain(), launch.member().orElseThrow()))
            outcome = com.imdomestic.chorus.effect.projectile.ProjectileFlight.Outcome.EXPIRED_SHOT;
        else if (launch.position().isEmpty()) outcome = com.imdomestic.chorus.effect.projectile.ProjectileFlight.Outcome.MISSING_POSITION;
        else if (launch.direction().isEmpty()) outcome = com.imdomestic.chorus.effect.projectile.ProjectileFlight.Outcome.MISSING_DIRECTION;
        else {
            var point = launch.position().orElseThrow(); var direction = launch.direction().orElseThrow();
            if (!point.dimension().equals(level.dimension().identifier().toString()) || !direction.dimension().equals(point.dimension())) outcome = com.imdomestic.chorus.effect.projectile.ProjectileFlight.Outcome.WRONG_DIMENSION;
            else if (MinecraftVisibility.trace(level, point, point).isEmpty()) outcome = com.imdomestic.chorus.effect.projectile.ProjectileFlight.Outcome.UNLOADED;
            else {
                var runtime = MinecraftEffectRuntime.installed(level).orElseThrow(() -> new IllegalStateException("Projectile needs a rule runtime"));
                if (runtime.failure().isPresent() || !runtime.program().program().version().equals(launch.continuation().version())) throw new IllegalStateException("Projectile runtime mismatch");
                var entity = new EffectProjectile(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_PROJECTILE.get(), level);
                entity.initialize(launch, runtime, launch.owner().isBlank() ? null : entities.apply(launch.owner()));
                if (level.addFreshEntity(entity) && !entity.isRemoved()) id = Optional.of(entity.getUUID().toString());
                else { entity.discard(); outcome = com.imdomestic.chorus.effect.projectile.ProjectileFlight.Outcome.REJECTED; }
            }
        }
        return new com.imdomestic.chorus.effect.projectile.ProjectileFlight.Receipt(launch, outcome, id);
    }
    private com.imdomestic.chorus.effect.object.WorldPickup.Receipt pickup(com.imdomestic.chorus.effect.object.WorldPickup.Spawn spawn) {
        var outcome = com.imdomestic.chorus.effect.object.WorldPickup.Outcome.SPAWNED;
        var id = Optional.<String>empty();
        if (spawn.position().isEmpty()) outcome = com.imdomestic.chorus.effect.object.WorldPickup.Outcome.MISSING_POSITION;
        else {
            var point = spawn.position().orElseThrow();
            var recipient = entities.apply(spawn.recipient());
            if (!point.dimension().equals(level.dimension().identifier().toString())) outcome = com.imdomestic.chorus.effect.object.WorldPickup.Outcome.WRONG_DIMENSION;
            else if (MinecraftVisibility.trace(level, point, point).isEmpty()) outcome = com.imdomestic.chorus.effect.object.WorldPickup.Outcome.UNLOADED;
            else if (!present(recipient) || !recipient.isAlive() || recipient.isSpectator() || !recipient.getUUID().toString().equals(spawn.recipient()))
                outcome = com.imdomestic.chorus.effect.object.WorldPickup.Outcome.MISSING_RECIPIENT;
            else {
                var runtime = MinecraftEffectRuntime.installed(level).orElseThrow(() -> new IllegalStateException("Pickup needs a rule runtime"));
                if (runtime.failure().isPresent() || !runtime.program().program().version().equals(spawn.continuation().version())) throw new IllegalStateException("Pickup runtime mismatch");
                var entity = new EffectObject(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_ENTITY.get(), level); entity.initialize(spawn, runtime);
                if (level.addFreshEntity(entity) && !entity.isRemoved()) id = Optional.of(entity.getUUID().toString());
                else { entity.discard(); outcome = com.imdomestic.chorus.effect.object.WorldPickup.Outcome.REJECTED; }
            }
        }
        return new com.imdomestic.chorus.effect.object.WorldPickup.Receipt(spawn, outcome, id);
    }
    private TargetQuery.Result select(TargetQuery query) {
        LivingEntity centerEntity = query.center() instanceof TargetQuery.EntityCenter center ? entities.apply(center.entity()) : null;
        var center = switch (query.center()) {
            case TargetQuery.EntityCenter ignored -> present(centerEntity) ? Optional.of(position(centerEntity, ((TargetQuery.EntityCenter) query.center()).anchor())) : Optional.<WorldPosition>empty();
            case TargetQuery.PositionCenter fixed -> fixed.position();
        };
        if (center.isEmpty()) return new TargetQuery.Result(query, TargetQuery.Outcome.MISSING_CENTER, java.util.List.of());
        var point = center.orElseThrow();
        if (!point.dimension().equals(level.dimension().identifier().toString())) return new TargetQuery.Result(query, TargetQuery.Outcome.WRONG_DIMENSION, java.util.List.of());
        if (query.shape() instanceof TargetShape.Cone cone) {
            if (cone.direction().isEmpty()) return new TargetQuery.Result(query, TargetQuery.Outcome.MISSING_DIRECTION, java.util.List.of());
            if (!cone.direction().orElseThrow().dimension().equals(point.dimension())) return new TargetQuery.Result(query, TargetQuery.Outcome.WRONG_DIMENSION, java.util.List.of());
        }
        var relative = query.relation() == TargetQuery.Relation.ANY ? null : entities.apply(query.relativeTo());
        if (query.relation() != TargetQuery.Relation.ANY && !present(relative)) return new TargetQuery.Result(query, TargetQuery.Outcome.MISSING_RELATIVE, java.util.List.of());
        var excluded = new java.util.HashSet<>(query.exclude());
        for (String reference : query.exclude()) {
            var entity = entities.apply(reference);
            if (present(entity)) excluded.add(entity.getUUID().toString());
        }
        var selected = new java.util.ArrayList<TargetQuery.Target>();
        // Loaded entities only: no chunk loads and no radius-proportional scan of unloaded chunk coordinates.
        // One chorus:meter is one block. Shapes and visibility use explicit sampled anchors, not hitbox intersection.
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof LivingEntity target) || !present(target) || !target.isAlive() || target.isSpectator()
                    || !query.includeCenter() && target == centerEntity) continue;
            String id = target.getUUID().toString();
            if (excluded.contains(id)) continue;
            var targetPoint = position(target, query.targetAnchor());
            var offset = new TargetShape.Offset(targetPoint.x() - point.x(), targetPoint.y() - point.y(), targetPoint.z() - point.z());
            if (!query.shape().contains(offset)) continue;
            boolean matches = switch (query.relation()) {
                case ANY -> true; case ALLIED -> relative.isAlliedTo(target); case NOT_ALLIED -> !relative.isAlliedTo(target);
            };
            if (matches && (!query.lineOfSight() || MinecraftVisibility.visible(level, point, targetPoint)))
                selected.add(query.shape() instanceof TargetShape.Sphere ? new TargetQuery.Target(id, offset.distance()) : new TargetQuery.Target(id, offset));
        }
        selected.sort(query.comparator());
        var targets = query.limit().isPresent() ? selected.subList(0, Math.min(query.limit().getAsInt(), selected.size())) : selected;
        return new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, targets);
    }
    private WorldPosition position(LivingEntity entity) { return position(entity, TargetQuery.Anchor.FEET); }
    private WorldPosition position(LivingEntity entity, TargetQuery.Anchor anchor) {
        return observePosition(entity, anchor);
    }
    /** Retained native damage targets can supply their actual world even after removal or transfer. */
    static WorldPosition observePosition(LivingEntity entity, TargetQuery.Anchor anchor) {
        double y = switch (anchor) { case FEET -> entity.getY(); case BODY -> entity.getBoundingBox().getCenter().y; case EYES -> entity.getEyeY(); };
        return new WorldPosition(entity.level().dimension().identifier().toString(), entity.getX(), y, entity.getZ());
    }
    private boolean present(LivingEntity entity) { return entity != null && !entity.isRemoved() && entity.level() == level; }
}
