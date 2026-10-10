package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import net.minecraft.world.entity.monster.Blaze;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.minecraft.world.entity.monster.Blaze$BlazeAttackGoal")
abstract class BlazeRangedAttackMixin {
    @Shadow @Final private Blaze blaze;
    // This call occurs only in the ranged emission branch, before sound and projectile creation.
    @Inject(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/monster/Blaze;isSilent()Z"),cancellable=true)
    private void chorus$ranged(CallbackInfo ci){if(!MinecraftNativeActions.ranged(blaze,blaze.getTarget(),"minecraft:small_fireball"))ci.cancel();}
}
