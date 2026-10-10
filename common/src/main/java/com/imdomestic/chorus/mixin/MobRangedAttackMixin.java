package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets={"net.minecraft.world.entity.monster.skeleton.AbstractSkeleton","net.minecraft.world.entity.monster.zombie.Drowned",
        "net.minecraft.world.entity.monster.Witch","net.minecraft.world.entity.monster.illager.Illusioner",
        "net.minecraft.world.entity.monster.illager.Pillager","net.minecraft.world.entity.monster.piglin.Piglin",
        "net.minecraft.world.entity.animal.golem.SnowGolem","net.minecraft.world.entity.animal.equine.Llama"})
abstract class MobRangedAttackMixin {
    @Inject(method="performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",at=@At("HEAD"),cancellable=true)
    private void chorus$ranged(LivingEntity target,float power,CallbackInfo ci){
        if(!MinecraftNativeActions.ranged((Mob)(Object)this,target,"minecraft:ranged_attack"))ci.cancel();
    }
}
