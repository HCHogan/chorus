package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeTracking;
import com.imdomestic.chorus.platform.minecraft.MinecraftNativeTracking.Credit;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.UUID;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShulkerBullet.class)
abstract class ShulkerTrackingMixin extends Projectile implements MinecraftNativeTracking.OwnerListener {
    @Shadow private EntityReference<Entity> finalTarget;
    @Unique private Credit chorus$credit;
    @Unique private UUID chorus$authorizedTarget;
    @Unique private boolean chorus$withheldTarget;
    @Unique private long chorus$ageTicks;
    protected ShulkerTrackingMixin(EntityType<? extends Projectile> type, Level level) { super(type, level); }

    @Override public void chorus$trackingOwnerChanged() {
        Credit before = chorus$credit == null ? Credit.EMPTY : chorus$credit;
        chorus$credit = before.reconcile(owner, level());
        if (!before.owner().equals(chorus$credit.owner())) chorus$authorizedTarget = null;
    }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void chorus$authorize(CallbackInfo ci) {
        chorus$withheldTarget = false;
        if (isRemoved()) { ci.cancel(); return; }
        if (level().isClientSide()) return;
        long age = chorus$ageTicks;
        if (chorus$ageTicks < Long.MAX_VALUE) chorus$ageTicks++;
        chorus$trackingOwnerChanged();
        Entity target = EntityReference.getEntity(finalTarget, level());
        if (!(target instanceof LivingEntity living) || !target.isAlive() || target.isSpectator()) { chorus$authorizedTarget = null; return; }
        var outcome = MinecraftNativeTracking.authorize(this, chorus$credit, living, !target.getUUID().equals(chorus$authorizedTarget), age);
        switch (outcome) {
            case UNMANAGED -> chorus$authorizedTarget = null;
            case ALLOWED -> chorus$authorizedTarget = target.getUUID();
            case RESTRICTED -> { chorus$authorizedTarget = null; chorus$withheldTarget = true; }
            case FAILED -> { chorus$authorizedTarget = null; discard(); ci.cancel(); }
        }
    }
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityReference;getEntity(Lnet/minecraft/world/entity/EntityReference;Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;"))
    private Entity chorus$target(Entity original) { return chorus$withheldTarget ? null : original; }
    @WrapOperation(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/projectile/ShulkerBullet;finalTarget:Lnet/minecraft/world/entity/EntityReference;", opcode = Opcodes.PUTFIELD))
    private void chorus$retainTarget(ShulkerBullet bullet, EntityReference<Entity> value, Operation<Void> original) {
        // Keep the real reference throughout tick, including native portal/save operations.
        if (!chorus$withheldTarget) original.call(bullet, value);
    }
    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    private void chorus$save(ValueOutput output, CallbackInfo ci) {
        chorus$trackingOwnerChanged(); output.store("chorus:tracking_credit", Credit.CODEC, chorus$credit);
        output.putLong("chorus:tracking_age", chorus$ageTicks);
    }
    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    private void chorus$load(ValueInput input, CallbackInfo ci) {
        chorus$credit = input.read("chorus:tracking_credit", Credit.CODEC).filter(c -> c.matches(owner))
                .orElseGet(() -> Credit.capture(owner, level()));
        chorus$authorizedTarget = null; chorus$withheldTarget = false;
        chorus$ageTicks = Math.max(0, input.getLongOr("chorus:tracking_age", 0));
    }
}
