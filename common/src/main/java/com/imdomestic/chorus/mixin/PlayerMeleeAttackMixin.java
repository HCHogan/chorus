package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class PlayerMeleeAttackMixin {
    @Inject(method="attack",at=@At("HEAD"),cancellable=true)
    private void chorus$melee(Entity victim,CallbackInfo ci){if(!MinecraftNativeActions.melee((Player)(Object)this,victim,"minecraft:player_melee"))ci.cancel();}
}
