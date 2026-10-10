package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.object.WorldConstruct;
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
    private long expiresAt;

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
        expiresAt = Math.addExact(runtime.state().engine().timeMicros(), spawn.parameters().lifetimeMicros());
        var p = command.parameters(); var at = command.position().orElseThrow();
        setPos(at.x(), at.y(), at.z());
        entityData.set(WIDTH, (float) p.width()); entityData.set(HEIGHT, (float) p.height());
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(p.health()); setHealth(getMaxHealth());
        addTag("chorus:construct"); addTag(command.kind()); command.tags().forEach(this::addTag);
        // Dimension refresh may move an entity to find space. Placement is explicitly checked by the executor.
        setPos(at.x(), at.y(), at.z());
    }
    public Optional<WorldConstruct.Spawn> construct() { return Optional.ofNullable(spawn); }
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
