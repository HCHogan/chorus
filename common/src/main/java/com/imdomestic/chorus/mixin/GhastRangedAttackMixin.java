package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Ghast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets="net.minecraft.world.entity.monster.Ghast$GhastShootFireballGoal")
abstract class GhastRangedAttackMixin {
    @WrapOperation(method={"canUse","tick"},at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/monster/Ghast;getTarget()Lnet/minecraft/world/entity/LivingEntity;"))
    private LivingEntity chorus$ranged(Ghast actor,Operation<LivingEntity> original){var target=original.call(actor);return target==null||MinecraftNativeActions.ranged(actor,target,"minecraft:large_fireball")?target:null;}
}
