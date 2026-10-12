package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Stationary, damageable, runtime-owned construct. Gameplay is supplied by ordinary effect bundles. */
public final class EffectConstruct extends Mob implements ItemSupplier {
    private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(EffectConstruct.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(EffectConstruct.class, EntityDataSerializers.FLOAT);
    private WorldConstruct.Spawn spawn;
    private MinecraftEffectRuntime runtime;
    private long createdAt, expiresAt;
    private boolean admitted;
    private Optional<RuleEngine.Signal> ended = Optional.empty();

    public EffectConstruct(EntityType<? extends EffectConstruct> type, Level level) {
        super(type, level); setNoAi(true); setNoGravity(true); setPersistenceRequired();
    }
    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 1).add(Attributes.MOVEMENT_SPEED, 0).add(Attributes.KNOCKBACK_RESISTANCE, 1);
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder data) {
        super.defineSynchedData(data); data.define(WIDTH, .5f); data.define(HEIGHT, .5f);
    }
    @Override protected EntityDimensions getDefaultDimensions(Pose pose) {
        if (entityData == null) return super.getDefaultDimensions(pose);
        return EntityDimensions.scalable(entityData.get(WIDTH), entityData.get(HEIGHT)).withEyeHeight(entityData.get(HEIGHT) / 2);
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key); if (key.equals(WIDTH) || key.equals(HEIGHT)) refreshDimensions();
    }
    void initialize(WorldConstruct.Spawn command, MinecraftEffectRuntime owner) {
        if (spawn != null || level().isClientSide()) throw new IllegalStateException("Construct is already initialized or not authoritative");
        spawn = Objects.requireNonNull(command); runtime = Objects.requireNonNull(owner);
        createdAt = runtime.state().engine().timeMicros();
        expiresAt = Math.addExact(createdAt, spawn.parameters().lifetimeMicros());
        var p = command.parameters(); var at = command.position().orElseThrow();
        setPos(at.x(), at.y(), at.z());
        entityData.set(WIDTH, (float) p.width()); entityData.set(HEIGHT, (float) p.height());
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(p.health()); setHealth(getMaxHealth());
        addTag("chorus:construct"); addTag(command.kind()); command.tags().forEach(this::addTag);
        // Dimension refresh may move an entity to find space. Placement is explicitly checked by the executor.
        setPos(at.x(), at.y(), at.z());
    }
    public Optional<WorldConstruct.Spawn> construct() { return Optional.ofNullable(spawn); }
    void admitted() {
        if (spawn == null || admitted || isRemoved()) throw new IllegalStateException("Construct was not successfully added");
        admitted = true;
    }
    public Optional<RuleEngine.Signal> ended() { return ended; }
    /** Entity.setRemoved also calls this hook for chunk unload, which bypasses Entity.remove. */
    @Override public void onRemoval(RemovalReason reason) {
        super.onRemoval(reason);
        if (!admitted || ended.isPresent() || !(level() instanceof ServerLevel server)) return;
        long now = runtime.nowMicros();
        var cause = reason == RemovalReason.KILLED || getHealth() <= 0 ? WorldConstruct.End.DESTROYED
                : now >= expiresAt ? WorldConstruct.End.EXPIRED : WorldConstruct.End.REMOVED;
        var signal = WorldConstruct.ended(spawn, getUUID().toString(), cause, reason.name().toLowerCase(Locale.ROOT), Math.subtractExact(now, createdAt));
        var positions = new HashMap<PositionQuery, Optional<WorldPosition>>();
        for (var anchor : TargetQuery.Anchor.values()) {
            var point = switch (anchor) { case FEET -> position(); case BODY -> getBoundingBox().getCenter(); case EYES -> getEyePosition(); };
            positions.put(new PositionQuery(getUUID().toString(), anchor), Optional.of(new WorldPosition(server.dimension().identifier().toString(), point.x, point.y, point.z)));
        }
        // The removed entity is unavailable now, but its last positions remain historical evidence.
        var observation = new EntityObservation(now, Map.of(getUUID().toString(), Optional.empty()), positions);
        ended = Optional.of(new RuleEngine.Signal(signal.type(), ((EffectEvent) signal.payload()).withObservedEntities(Optional.of(observation))));
        runtime.recordNativeLifecycle(ended.orElseThrow());
    }
    boolean ownedBy(MinecraftEffectRuntime candidate) { return runtime == candidate; }
    private boolean active() {
        return runtime != null && level() instanceof ServerLevel server && MinecraftEffectRuntime.installed(server).orElse(null) == runtime
                && runtime.failure().isEmpty() && runtime.nowMicros() < expiresAt;
    }
    @Override public boolean isAlive() { return super.isAlive() && (level().isClientSide() || active()); }
    @Override public void tick() {
        if (!level().isClientSide() && !isAlive()) { discard(); return; }
        super.tick();
    }
    @Override public void travel(Vec3 input) { setDeltaMovement(Vec3.ZERO); }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canUsePortal(boolean ignored) { return false; }
    @Override protected boolean shouldDropLoot(ServerLevel level) { return false; }
    @Override public boolean shouldDropExperience() { return false; }
    @Override public boolean canBreatheUnderwater() { return true; }
    /** Placeholder only; the dimensions and gameplay identity are independent of this icon. */
    @Override public ItemStack getItem() { return new ItemStack(Items.IRON_NUGGET); }
}
