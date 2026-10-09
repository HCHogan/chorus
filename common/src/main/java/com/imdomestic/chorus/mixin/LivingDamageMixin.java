package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.DamageCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DeathProtection;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
abstract class LivingDamageMixin {
    @WrapMethod(method = "hurtServer")
    private boolean chorus$capture(ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
        return DamageCapture.hurt((LivingEntity) (Object) this, source, amount, 1, scaled -> original.call(level, source, scaled));
    }
    @WrapMethod(method = "setHealth")
    private void chorus$health(float health, Operation<Void> original) {
        var entity = (LivingEntity) (Object) this; float before = entity.getHealth();
        original.call(DamageCapture.limitHealth(entity, health));
        DamageCapture.health(entity, before, entity.getHealth());
    }
    @WrapMethod(method = "setAbsorptionAmount")
    private void chorus$absorption(float amount, Operation<Void> original) {
        var entity = (LivingEntity) (Object) this; float before = entity.getAbsorptionAmount();
        original.call(amount); DamageCapture.absorption(entity, before, entity.getAbsorptionAmount());
    }
    @WrapMethod(method = "isInvulnerableTo")
    private boolean chorus$immunity(ServerLevel level, DamageSource source, Operation<Boolean> original) {
        boolean immune = original.call(level, source);
        if (immune) DamageCapture.immune((LivingEntity) (Object) this, source);
        return immune;
    }
    @WrapOperation(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private boolean chorus$fireResistance(LivingEntity entity, Holder<MobEffect> effect, Operation<Boolean> original) {
        boolean active = original.call(entity, effect);
        if (active && effect.equals(MobEffects.FIRE_RESISTANCE)) DamageCapture.fireImmune(entity);
        return active;
    }
    @WrapOperation(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;applyItemBlocking(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)F"))
    private float chorus$blocking(LivingEntity entity, ServerLevel level, DamageSource source, float amount, Operation<Float> original) {
        float blocked = original.call(entity, level, source, amount);
        if (blocked > 0) DamageCapture.blocked(entity);
        return blocked;
    }
    @WrapOperation(method = "checkTotemDeathProtection", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/component/DeathProtection;applyEffects(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void chorus$protection(DeathProtection protection, ItemStack item, LivingEntity entity, Operation<Void> original) {
        original.call(protection, item, entity);
        DamageCapture.protectedBy(entity, BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
    }
    @WrapOperation(method = "die", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;broadcastEntityEvent(Lnet/minecraft/world/entity/Entity;B)V"))
    private void chorus$confirmedDeath(Level level, Entity entity, byte event, Operation<Void> original, DamageSource source) {
        original.call(level, entity, event);
        if (event == 3) DamageCapture.death((LivingEntity) entity, source);
    }
}
