package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.projectile.ProjectileTracking;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.effect.target.WorldPosition;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import java.util.*;

/** Server-authoritative swept projectile. Contact bodies and numerical inputs are pinned at launch. */
public final class EffectProjectile extends Projectile implements ItemSupplier {
    private static final EntityDataAccessor<Float> GRAVITY = SynchedEntityData.defineId(EffectProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DRAG = SynchedEntityData.defineId(EffectProjectile.class, EntityDataSerializers.FLOAT);
    private ProjectileFlight.Launch launch;
    private MinecraftEffectRuntime runtime;
    private long ageMicros;
    private boolean terminal;
    private ProjectileFlight.Progress progress = ProjectileFlight.Progress.EMPTY;
    private final Set<UUID> inside = new HashSet<>();
    private UUID trackingTarget;
    private static final double SEPARATION = 1e-5;
    public ProjectileFlight.Progress progress() { return progress; }
    public Optional<String> trackingTarget() { return Optional.ofNullable(trackingTarget).map(UUID::toString); }
    public EffectProjectile(EntityType<? extends EffectProjectile> type, Level level) { super(type, level); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder data) { data.define(GRAVITY, 0.0f); data.define(DRAG, 1.0f); }
    @Override public ItemStack getItem() { return new ItemStack(Items.AMETHYST_SHARD); }
    @Override public boolean canUsePortal(boolean ignored) { return false; }
    public void initialize(ProjectileFlight.Launch launch, MinecraftEffectRuntime runtime, Entity owner) {
        if (this.launch != null || level().isClientSide()) throw new IllegalStateException("Projectile already initialized or not authoritative");
        this.launch = launch; this.runtime = runtime;
        var point = launch.position().orElseThrow(); var direction = launch.direction().orElseThrow(); var parameters = launch.parameters();
        setPos(point.x(), point.y(), point.z()); setOwner(owner);
        setDeltaMovement(direction.x() * parameters.speed() / 20, direction.y() * parameters.speed() / 20, direction.z() * parameters.speed() / 20);
        entityData.set(GRAVITY, (float) parameters.gravity()); entityData.set(DRAG, (float) parameters.drag());
        updateRotation();
    }
    public Optional<ProjectileFlight.Launch> launch() { return Optional.ofNullable(launch); }
    private void abandon() { progress = progress.abandon(); terminal = true; trackingTarget = null; discard(); }
    private WorldPosition point(Vec3 p) { return new WorldPosition(level().dimension().identifier().toString(), p.x, p.y, p.z); }
    @Override public void tick() {
        if (terminal) return;
        if (level() instanceof ServerLevel server) {
            if (launch == null || MinecraftEffectRuntime.installed(server).orElse(null) != runtime || runtime.failure().isPresent()) { abandon(); return; }
            runtime.prepare();
            if (isRemoved() || runtime.failure().isPresent() || launch.member().filter(member ->
                    !com.imdomestic.chorus.effect.projectile.ShotGroups.active(runtime.state().engine().domain(), member)).isPresent()) { abandon(); return; }
        }
        super.tick();
        // Semi-implicit Euler: gravity, then per-tick drag, then movement. Units are blocks and 50 ms ticks.
        double gravity = launch == null ? entityData.get(GRAVITY) : launch.parameters().gravity();
        double drag = launch == null ? entityData.get(DRAG) : launch.parameters().drag();
        var movement = getDeltaMovement().add(0, -gravity / 400.0, 0).scale(drag);
        setDeltaMovement(movement); var from = position(); var to = from.add(movement); updateRotation();
        if (!(level() instanceof ServerLevel server)) { setPos(to); return; }
        steer(server, false);
        ageMicros = Math.addExact(ageMicros, 50_000);
        double remaining = 1;
        while (!terminal && remaining > 0) {
            from = position(); movement = getDeltaMovement().scale(remaining); to = from.add(movement);
            var terrain = MinecraftVisibility.projectile(server, point(from), point(to));
            if (terrain.isEmpty()) { observe(ProjectileFlight.End.UNLOADED, from, Optional.empty(), 0, 0, 0, false); return; }
            var block = terrain.orElseThrow(); var end = block.getType() == HitResult.Type.BLOCK ? block.getLocation() : to;
            var entity = entityHit(server, from, end, movement);
            if (entity != null) {
                remaining = remainingAfter(remaining, from, entity.getLocation(), movement);
                inside.add(entity.getEntity().getUUID());
                if (!observe(ProjectileFlight.End.ENTITY, entity.getLocation(), Optional.of(entity.getEntity().getUUID().toString()), 0, 0, 0, false)) return;
                setPos(entity.getLocation().add(getDeltaMovement().normalize().scale(SEPARATION)));
                steer(server, true);
            } else if (block.getType() == HitResult.Type.BLOCK) {
                remaining = remainingAfter(remaining, from, end, movement);
                var normal = block.getDirection(); var n = new Vec3(normal.getStepX(), normal.getStepY(), normal.getStepZ());
                boolean embedded = block.isInside() || getDeltaMovement().dot(n) >= 0;
                if (!embedded && launch.parameters().collision().blockBounces().allows(progress.bounces())) {
                    var v = getDeltaMovement(); setDeltaMovement(v.subtract(n.scale(2 * v.dot(n))).scale(launch.parameters().collision().restitution()));
                    updateRotation();
                }
                if (!observe(ProjectileFlight.End.BLOCK, end, Optional.empty(), n.x, n.y, n.z, embedded)) return;
                setPos(end.add(n.scale(SEPARATION)));
                steer(server, true);
                if (getDeltaMovement().lengthSqr() == 0) break;
            } else { setPos(to); remaining = 0; }
        }
        if (!terminal && ageMicros >= launch.parameters().lifetimeMicros()) observe(ProjectileFlight.End.EXPIRED, position(), Optional.empty(), 0, 0, 0, false);
    }
    private static double remainingAfter(double remaining, Vec3 from, Vec3 contact, Vec3 movement) {
        double length = movement.length();
        return length == 0 ? remaining : remaining * Math.max(0, 1 - from.distanceTo(contact) / length);
    }
    private void refreshInside(ServerLevel level, Vec3 from) {
        inside.removeIf(id -> { var e = level.getEntity(id); return e == null || !e.getBoundingBox().inflate(0.125).contains(from); });
    }
    private Vec3 anchor(LivingEntity target, TargetQuery.Anchor anchor) {
        double y = switch (anchor) { case FEET -> target.getY(); case BODY -> target.getBoundingBox().getCenter().y; case EYES -> target.getEyeY(); };
        return new Vec3(target.getX(), y, target.getZ());
    }
    private WorldDirection direction(Vec3 vector) { return new WorldDirection(level().dimension().identifier().toString(), vector.x, vector.y, vector.z); }
    private boolean trackable(ServerLevel level, LivingEntity candidate, ProjectileTracking.Policy policy, boolean acquire) {
        if (!candidate.isAlive() || candidate.isRemoved() || candidate.isSpectator() || candidate.level() != level
                || candidate.getUUID().toString().equals(launch.owner()) || inside.contains(candidate.getUUID())
                || !progress.canHit(candidate.getUUID().toString(), launch.parameters().collision())) return false;
        var offset = anchor(candidate, policy.anchor()).subtract(position());
        if (offset.lengthSqr() == 0 || offset.length() > policy.radius()) return false;
        if (policy.relation() != TargetQuery.Relation.ANY) {
            var owner = getOwner(); if (owner == null || owner.isRemoved() || owner.level() != level) return false;
            boolean allied = owner.isAlliedTo(candidate);
            if (policy.relation() == TargetQuery.Relation.ALLIED ? !allied : allied) return false;
        }
        if (acquire && !ProjectileTracking.inCone(direction(getDeltaMovement()), direction(offset), policy.acquisitionAngle())) return false;
        return !policy.lineOfSight() || MinecraftVisibility.visible(level, point(position()), point(anchor(candidate, policy.anchor())));
    }
    private void steer(ServerLevel level, boolean contact) {
        var policy = launch.parameters().tracking().orElse(null);
        if (policy == null || contact && !policy.redirectOnContact()) return;
        if (getDeltaMovement().lengthSqr() == 0) { trackingTarget = null; return; }
        refreshInside(level, position());
        var retained = trackingTarget == null || contact ? null : level.getEntity(trackingTarget);
        LivingEntity selected = retained instanceof LivingEntity living && trackable(level, living, policy, false) ? living : null;
        if (selected == null) {
            double closest = Double.POSITIVE_INFINITY;
            // Loaded entities only, with identity retention to avoid chasing whichever target becomes marginally nearer.
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity living) || !trackable(level, living, policy, true)) continue;
                double distance = position().distanceToSqr(anchor(living, policy.anchor()));
                if (distance < closest || distance == closest && (selected == null || living.getUUID().compareTo(selected.getUUID()) < 0)) { selected = living; closest = distance; }
            }
        }
        trackingTarget = selected == null ? null : selected.getUUID();
        if (selected == null) return; // Losing a target keeps ballistic flight; no guessed target or chunk loading.
        double speed = getDeltaMovement().length();
        var turned = ProjectileTracking.turn(direction(getDeltaMovement()), direction(anchor(selected, policy.anchor()).subtract(position())), contact ? 180 : policy.turnRate() / 20);
        setDeltaMovement(turned.x() * speed, turned.y() * speed, turned.z() * speed); updateRotation();
    }
    private EntityHitResult entityHit(ServerLevel level, Vec3 from, Vec3 to, Vec3 movement) {
        // Reentry can count again only after physically leaving the expanded hitbox; no tick cooldown or global dedup.
        refreshInside(level, from);
        EntityHitResult best = null; double distance = Double.POSITIVE_INFINITY;
        for (var entity : level.getEntities(this, getBoundingBox().expandTowards(movement).inflate(0.125),
                e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator()
                        && !e.getUUID().toString().equals(launch.owner()) && !inside.contains(e.getUUID())
                        && progress.canHit(e.getUUID().toString(), launch.parameters().collision()))) {
            var box = entity.getBoundingBox().inflate(0.125);
            var contact = box.contains(from) ? Optional.of(from) : box.clip(from, to);
            if (contact.isEmpty()) continue;
            double d = from.distanceToSqr(contact.orElseThrow());
            if (d < distance || d == distance && (best == null || entity.getUUID().compareTo(best.getEntity().getUUID()) < 0)) {
                best = new EntityHitResult(entity, contact.orElseThrow()); distance = d;
            }
        }
        return best;
    }
    private boolean observe(ProjectileFlight.End reason, Vec3 at, Optional<String> target, double nx, double ny, double nz, boolean embedded) {
        if (terminal) return false;
        progress = progress.contact(launch.parameters().collision(), reason, target, embedded);
        terminal = progress.terminal(); setPos(at);
        if (terminal) discard(); // Commit collision bookkeeping before reactions; failures never replay the contact.
        try {
            runtime.start(launch.finish(new ProjectileFlight.Impact(reason, point(at), target, nx, ny, nz, ageMicros,
                    progress.sequence(), progress.bounces(), progress.entityContacts(), target.map(id -> progress.hits().get(id)).orElse(0L), terminal)));
        } catch (RuntimeException error) {
            abandon(); com.imdomestic.chorus.Constants.LOG.error("Projectile contact action failed; consumed projectile will not replay", error);
        }
        if (isRemoved() || runtime.failure().isPresent() || MinecraftEffectRuntime.installed((ServerLevel) level()).orElse(null) != runtime) abandon();
        return !terminal;
    }
}
