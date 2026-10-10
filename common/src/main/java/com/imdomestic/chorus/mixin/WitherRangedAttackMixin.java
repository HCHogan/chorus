package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WitherBoss.class)
abstract class WitherRangedAttackMixin {
    // The coordinate overload also covers side heads and untargeted dangerous skulls.
    @Inject(method="performRangedAttack(IDDDZ)V",at=@At("HEAD"),cancellable=true)
    private void chorus$ranged(int head,double x,double y,double z,boolean dangerous,CallbackInfo ci){
        if(!MinecraftNativeActions.ranged((WitherBoss)(Object)this,null,"minecraft:wither_skull"))ci.cancel();
    }
}
