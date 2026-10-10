package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.object.WorldPickup;
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
            var calculated = runtime.program().calculate(runtime.state().engine().domain(), pickup.recipient(), query, attraction.profile(), new Measure(attraction.radius(), Unit.METER), List.of()).output();
            if (!calculated.unit().equals(Unit.METER)) throw new IllegalArgumentException("Pickup attraction profile must return meters");
            double radius = Numbers.nonnegative(calculated.value(), "collector attraction radius");
            if (distance > radius || distance == 0) return;
            var to = position().add(center.subtract(position()).scale(Math.min(1, attraction.speed() / 20 / distance)));
            var terrain = MinecraftVisibility.projectile(server, point(position()), point(to));
            if (terrain.isEmpty() || terrain.orElseThrow().getType() != HitResult.Type.MISS) return;
            setPos(to);
            if (canCollect(server, center, to.distanceTo(center))) finish(WorldPickup.End.COLLECTED, Optional.of(pickup.recipient()), age);
        } catch (RuntimeException error) {
            abandon(); com.imdomestic.chorus.Constants.LOG.error("World object failed; consumed pickup will not replay", error);
        }
    }
    private boolean canCollect(ServerLevel server, Vec3 center, double distance) {
        return distance <= pickup.parameters().radius() && MinecraftVisibility.visible(server, point(position()), point(center));
    }
    private void finish(WorldPickup.End end, Optional<String> collector, long age) {
        var signal = pickup.finish(new WorldPickup.Contact(end, point(position()), collector, age));
        abandon(); // Claim before executing any reward; an unknown world outcome must never replay.
        runtime.start(signal);
    }
}
