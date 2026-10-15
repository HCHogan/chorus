package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.object.PickupCollection;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.stat.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import java.util.*;

/** Short-lived generic world object. The first implemented component is one private logical pickup. */
public final class EffectObject extends Entity implements ItemSupplier {
    private WorldPickup.Spawn pickup;
    private MinecraftEffectRuntime runtime;
    private long createdMicros;
    private boolean consumed;
    public EffectObject(EntityType<? extends EffectObject> type, Level level) { super(type, level); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder data) {}
    @Override protected void readAdditionalSaveData(ValueInput input) {}
    @Override protected void addAdditionalSaveData(ValueOutput output) {}
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
    @Override public boolean canUsePortal(boolean ignored) { return false; }
    @Override public ItemStack getItem() { return new ItemStack(Items.GLOWSTONE_DUST); }
    public Optional<WorldPickup.Spawn> pickup() { return Optional.ofNullable(pickup); }
    public void initialize(WorldPickup.Spawn pickup, MinecraftEffectRuntime runtime) {
        if (this.pickup != null || level().isClientSide()) throw new IllegalStateException("Object already initialized or not authoritative");
        this.pickup = Objects.requireNonNull(pickup); this.runtime = Objects.requireNonNull(runtime);
        createdMicros = runtime.state().engine().timeMicros();
        var at = pickup.position().orElseThrow(); setPos(at.x(), at.y(), at.z()); setNoGravity(true);
    }
    private WorldPosition point(Vec3 p) { return new WorldPosition(level().dimension().identifier().toString(), p.x, p.y, p.z); }
    private void abandon() { consumed = true; discard(); }
    boolean ownedBy(MinecraftEffectRuntime candidate) { return runtime == candidate; }
    boolean remoteCandidate(MinecraftEffectRuntime candidate, PickupCollection.Query query, LivingEntity collector) {
        if (consumed || isRemoved() || pickup == null || runtime != candidate || runtime.failure().isPresent()
                || !query.collector().equals(pickup.recipient()) || !query.kinds().contains(pickup.kind())
                || runtime.state().engine().timeMicros() - createdMicros >= pickup.parameters().lifetimeMicros()) return false;
        var server = (ServerLevel) level();
        return position().distanceTo(collector.position()) <= query.radius()
                && MinecraftVisibility.trace(server, point(position()), point(position())).isPresent()
                && (!query.lineOfSight() || MinecraftVisibility.visible(server, point(position()), point(collector.position())));
    }
    PickupCollection.Claimed claim(PickupCollection.Claim claim) {
        var server = (ServerLevel) level(); var query = claim.query();
        var outcome = PickupCollection.ClaimOutcome.COLLECTED;
        long age = runtime == null ? 0 : Math.subtractExact(runtime.state().engine().timeMicros(), createdMicros);
        var collector = MinecraftPickupCollection.collector(server, query.collector());
        if (consumed || isRemoved() || pickup == null || !getUUID().toString().equals(claim.entry().entity()) || !pickup.equals(claim.entry().spawn())
                || MinecraftEffectRuntime.installed(server).orElse(null) != runtime || runtime.failure().isPresent()
                || MinecraftVisibility.trace(server, point(position()), point(position())).isEmpty()) outcome = PickupCollection.ClaimOutcome.UNAVAILABLE;
        else if (age >= pickup.parameters().lifetimeMicros()) outcome = PickupCollection.ClaimOutcome.EXPIRED;
        else if (collector == null) outcome = PickupCollection.ClaimOutcome.MISSING_COLLECTOR;
        else if (position().distanceTo(collector.position()) > query.radius()) outcome = PickupCollection.ClaimOutcome.OUT_OF_RANGE;
        else if (query.lineOfSight() && !MinecraftVisibility.visible(server, point(position()), point(collector.position()))) outcome = PickupCollection.ClaimOutcome.OCCLUDED;
        else if (!runtime.canCollectPickup(pickup)) outcome = PickupCollection.ClaimOutcome.INELIGIBLE;
        if (outcome != PickupCollection.ClaimOutcome.COLLECTED) return new PickupCollection.Claimed(claim, outcome, Optional.empty());
        var contact = new WorldPickup.Contact(WorldPickup.End.COLLECTED, point(position()), Optional.of(query.collector()), age);
        var result = new PickupCollection.Claimed(claim, outcome, Optional.of(contact));
        abandon(); // The original reward body resumes only after this physical claim is confirmed.
        return result;
    }
    @Override public void tick() {
        if (consumed || isRemoved()) return;
        super.tick();
        if (!(level() instanceof ServerLevel server)) return;
        if (pickup == null || MinecraftEffectRuntime.installed(server).orElse(null) != runtime || runtime.failure().isPresent()) { abandon(); return; }
        try {
            runtime.prepare();
            if (isRemoved() || runtime.failure().isPresent() || MinecraftEffectRuntime.installed(server).orElse(null) != runtime) { abandon(); return; }
            long age = Math.subtractExact(runtime.state().engine().timeMicros(), createdMicros);
            if (age >= pickup.parameters().lifetimeMicros()) { finish(WorldPickup.End.EXPIRED, Optional.empty(), age); return; }
            // Never load a missing chunk or redirect a private unit to an unrelated nearby player.
            if (MinecraftVisibility.trace(server, point(position()), point(position())).isEmpty()) { abandon(); return; }
            var entity = server.getEntity(UUID.fromString(pickup.recipient()));
            if (!(entity instanceof LivingEntity collector) || !collector.isAlive() || collector.isRemoved() || collector.isSpectator()) return;
            var center = collector.position(); double distance = position().distanceTo(center);
            if (canCollect(server, center, distance)) { finish(WorldPickup.End.COLLECTED, Optional.of(pickup.recipient()), age); return; }
            var attraction = pickup.parameters().attraction().orElse(null);
            if (attraction == null || attraction.speed() == 0) return;
            var query = new EffectEvent(pickup.recipient(), pickup.recipient(), pickup.origin(), Set.of(pickup.kind()), Map.of());
            var calculated = runtime.calculate(pickup.recipient(), query, attraction.profile(), new Measure(attraction.radius(), Unit.METER)).output();
            if (!calculated.unit().equals(Unit.METER)) throw new IllegalArgumentException("Pickup attraction profile must return meters");
            double radius = Numbers.nonnegative(calculated.value(), "collector attraction radius");
            if (distance > radius || distance == 0) return;
            var to = position().add(center.subtract(position()).scale(Math.min(1, attraction.speed() / 20 / distance)));
            var terrain = MinecraftVisibility.projectile(server, point(position()), point(to));
            if (terrain.isEmpty() || terrain.orElseThrow().unavailable()
                    || terrain.orElseThrow().hit().getType() != HitResult.Type.MISS) return;
            setPos(to);
            if (canCollect(server, center, to.distanceTo(center))) finish(WorldPickup.End.COLLECTED, Optional.of(pickup.recipient()), age);
        } catch (RuntimeException error) {
            abandon(); com.imdomestic.chorus.Constants.LOG.error("World object failed; consumed pickup will not replay", error);
        }
    }
    private boolean canCollect(ServerLevel server, Vec3 center, double distance) {
        return distance <= pickup.parameters().radius() && MinecraftVisibility.visible(server, point(position()), point(center))
                && runtime.canCollectPickup(pickup);
    }
    private void finish(WorldPickup.End end, Optional<String> collector, long age) {
        var signal = pickup.finish(new WorldPickup.Contact(end, point(position()), collector, age));
        abandon(); // Claim before executing any reward; an unknown world outcome must never replay.
        runtime.start(signal);
    }
}
