package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.DamageCapture;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Player.class)
abstract class PlayerShieldMixin {
    @ModifyArg(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getDamageAfterArmorAbsorb(Lnet/minecraft/world/damagesource/DamageSource;F)F"), index = 1)
    private float chorus$shields(float amount) { return DamageCapture.shield((Player) (Object) this, amount); }
}
