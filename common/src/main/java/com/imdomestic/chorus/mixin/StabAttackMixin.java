package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({LivingEntity.class,Player.class})
abstract class StabAttackMixin {
    @Inject(method="stabAttack",at=@At("HEAD"),cancellable=true)
    private void chorus$melee(EquipmentSlot slot,Entity victim,float damage,boolean hurt,boolean knockback,boolean dismount,CallbackInfoReturnable<Boolean> cir){
        if(!MinecraftNativeActions.melee((LivingEntity)(Object)this,victim,"minecraft:stab"))cir.setReturnValue(false);
    }
}
