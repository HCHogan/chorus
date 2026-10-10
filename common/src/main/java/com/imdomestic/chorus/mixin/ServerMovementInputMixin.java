package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftMovementInput;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerMovementInputMixin {
    @Shadow public ServerPlayer player;
    @ModifyExpressionValue(method="handlePlayerInput",at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/game/ServerboundPlayerInputPacket;input()Lnet/minecraft/world/entity/player/Input;"))
    private Input chorus$input(Input input){return MinecraftMovementInput.filter(input,MinecraftMovementInput.mask(player));}
    @Inject(method="handleAcceptPlayerLoad",at=@At("TAIL"))
    private void chorus$loaded(net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket packet,CallbackInfo ci){com.imdomestic.chorus.network.MovementInputNetwork.sync(player,player);com.imdomestic.chorus.network.HorizontalSpeedNetwork.sync(player,player);}
}
