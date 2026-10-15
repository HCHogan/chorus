package com.imdomestic.chorus.test.mixin;

import com.imdomestic.chorus.test.NativeTransferTestProbe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only observation after real source removal and before destination insertion. */
@Mixin(ServerLevel.class)
abstract class NativeTransferFailureMixin {
    @Inject(method="removePlayerImmediately",at=@At("RETURN"))
    private void chorus$afterTransferRemoval(ServerPlayer player,Entity.RemovalReason reason,CallbackInfo callback) {
        if(reason==Entity.RemovalReason.CHANGED_DIMENSION)NativeTransferTestProbe.removed(player);
    }
}
