package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.PlayerAbilities;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerAbilitiesMixin {
    @Inject(method="restoreFrom", at=@At("TAIL"))
    private void chorus$transferAbilities(ServerPlayer previous, boolean restoreAll, CallbackInfo ci) {
        PlayerAbilities.get((ServerPlayer) (Object) this).transferFrom(PlayerAbilities.get(previous));
    }
}
