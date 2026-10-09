package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.HealingCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
abstract class LivingHealingMixin {
    @WrapMethod(method = "heal")
    private void chorus$healing(float amount, Operation<Void> original) {
        HealingCapture.heal((LivingEntity) (Object) this, () -> original.call(amount));
    }
    @WrapOperation(method = "heal", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setHealth(F)V"))
    private void chorus$healingWrite(LivingEntity entity, float health, Operation<Void> original) {
        HealingCapture.healthWrite(entity, health, value -> original.call(entity, value));
    }
}
