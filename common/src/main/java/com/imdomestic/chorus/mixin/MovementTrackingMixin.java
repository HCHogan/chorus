package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.network.MovementInputNetwork;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
abstract class MovementTrackingMixin {
    @Shadow @Final private Entity entity;
    @Inject(method="addPairing",at=@At("TAIL"))
    private void chorus$pair(ServerPlayer player,CallbackInfo ci){if(entity instanceof LivingEntity living){MovementInputNetwork.sync(living,player);com.imdomestic.chorus.network.HorizontalSpeedNetwork.sync(living,player);}}
}
