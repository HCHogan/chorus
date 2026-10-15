package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftPlayerTransfer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Publish character ownership after native insertion, before post-teleport callbacks can execute rules. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerTransferMixin {
    @WrapMethod(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;")
    private ServerPlayer chorus$transfer(TeleportTransition transition, Operation<ServerPlayer> original) {
        var handoff = MinecraftPlayerTransfer.begin((ServerPlayer) (Object) this, transition.newLevel());
        try {
            var result = original.call(transition);
            MinecraftPlayerTransfer.returned(handoff, result);
            return result;
        } catch (Throwable failure) {
            MinecraftPlayerTransfer.failed(handoff, failure);
            throw failure;
        } finally {
            MinecraftPlayerTransfer.end(handoff);
        }
    }

    @WrapOperation(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addDuringTeleport(Lnet/minecraft/world/entity/Entity;)V"))
    private void chorus$inserted(ServerLevel destination, Entity entity, Operation<Void> original) {
        original.call(destination, entity);
        MinecraftPlayerTransfer.inserted((ServerPlayer) entity, destination);
    }
}
