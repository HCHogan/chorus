package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.minecraft.world.entity.monster.cubemob.AbstractCubeMob")
abstract class CubeMeleeAttackMixin {
    @Inject(method="dealDamage",at=@At("HEAD"),cancellable=true)
    private void chorus$melee(LivingEntity victim,CallbackInfo ci){if(!MinecraftNativeActions.melee((LivingEntity)(Object)this,victim,"minecraft:cube_contact"))ci.cancel();}
}
