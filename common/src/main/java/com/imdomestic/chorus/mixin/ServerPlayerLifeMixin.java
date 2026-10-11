package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftEntityLife;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerLifeMixin {
    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void chorus$restoreLife(ServerPlayer previous, boolean restoreAll, CallbackInfo ci) {
        MinecraftEntityLife.get((ServerPlayer) (Object) this).restoreFrom(MinecraftEntityLife.get(previous), restoreAll);
    }
}
