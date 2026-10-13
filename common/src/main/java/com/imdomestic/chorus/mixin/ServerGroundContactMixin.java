package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftGroundContact;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGroundContactMixin {
    @Shadow public ServerPlayer player;
    @WrapMethod(method = "handlePlayerPositionChange")
    private void chorus$packet(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean horizontalCollision, Operation<Void> original) {
        try (var scope = MinecraftGroundContact.packet(player)) { original.call(x, y, z, yaw, pitch, onGround, horizontalCollision); }
    }
    @WrapOperation(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;setOnGroundWithMovement(ZZLnet/minecraft/world/phys/Vec3;)V"))
    private void chorus$accepted(ServerPlayer player, boolean onGround, boolean horizontalCollision, Vec3 movement, Operation<Void> original) {
        original.call(player, onGround, horizontalCollision, movement); MinecraftGroundContact.accepted(player);
    }
}
