package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets="net.minecraft.world.entity.monster.Guardian$GuardianAttackGoal")
abstract class GuardianRangedAttackMixin {
    @WrapOperation(method={"canUse","tick"},at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/monster/Guardian;getTarget()Lnet/minecraft/world/entity/LivingEntity;"))
    private LivingEntity chorus$ranged(Guardian actor,Operation<LivingEntity> original){var target=original.call(actor);return target==null||MinecraftNativeActions.ranged(actor,target,"minecraft:guardian_beam")?target:null;}
}
