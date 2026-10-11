package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.DamageCapture;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;

@Mixin(LivingEntity.class)
abstract class LivingShieldMixin {
    @ModifyArg(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getDamageAfterArmorAbsorb(Lnet/minecraft/world/damagesource/DamageSource;F)F"), index = 1)
    private float chorus$shields(float amount) { return DamageCapture.shield((LivingEntity) (Object) this, amount); }
    /** Third float is absorbedDamage: absorption accounting is complete before changing the damage argument. */
    @ModifyVariable(method = "actuallyHurt", at = @At("STORE"), ordinal = 2)
    private float chorus$health(float absorbedDamage, @Local(argsOnly = true) LocalFloatRef damage) {
        damage.set(DamageCapture.healthDamage((LivingEntity) (Object) this, damage.get()));
        return absorbedDamage;
    }
}
