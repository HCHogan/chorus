package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets="net.minecraft.world.entity.monster.Shulker$ShulkerAttackGoal")
abstract class ShulkerRangedAttackMixin {
    @WrapOperation(method={"canUse","tick"},at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/monster/Shulker;getTarget()Lnet/minecraft/world/entity/LivingEntity;"))
    private LivingEntity chorus$ranged(Shulker actor,Operation<LivingEntity> original){var target=original.call(actor);return target==null||MinecraftNativeActions.ranged(actor,target,"minecraft:shulker_bullet")?target:null;}
}
