package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.PlayerActor;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerActorMixin {
    @Inject(method="restoreFrom", at=@At("TAIL"))
    private void chorus$transferActor(ServerPlayer previous, boolean restoreAll, CallbackInfo ci) {
        PlayerActor.get((ServerPlayer) (Object) this).transferFrom(PlayerActor.get(previous));
    }
}
