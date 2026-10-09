package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.DamageCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** ServerPlayer overrides die without delegating to LivingEntity. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerDeathMixin {
    @WrapMethod(method = "hurtServer")
    private boolean chorus$capture(ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
        return DamageCapture.hurt((ServerPlayer) (Object) this, source, amount, 3, scaled -> original.call(level, source, scaled));
    }
    @WrapMethod(method = "isInvulnerableTo")
    private boolean chorus$immunity(ServerLevel level, DamageSource source, Operation<Boolean> original) {
        boolean immune = original.call(level, source);
        if (immune) DamageCapture.immune((ServerPlayer) (Object) this, source);
        return immune;
    }
    @WrapOperation(method = "die", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;broadcastEntityEvent(Lnet/minecraft/world/entity/Entity;B)V"))
    private void chorus$confirmedDeath(ServerLevel level, Entity entity, byte event, Operation<Void> original, DamageSource source) {
        original.call(level, entity, event);
        if (event == 3) DamageCapture.death((LivingEntity) entity, source);
    }
}
