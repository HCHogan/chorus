package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerEquipmentMixin {
    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void chorus$transferEquipment(ServerPlayer oldPlayer, boolean restoreAll, CallbackInfo ci) {
        PlayerEquipment.get((ServerPlayer) (Object) this).transferFrom(PlayerEquipment.get(oldPlayer));
    }
}
