package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftPlayerTransfer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Respawn replaces the entity and does not call ServerPlayer.teleport. Publish after its own insertion. */
@Mixin(PlayerList.class)
abstract class PlayerListRespawnMixin {
    @Unique private static final ThreadLocal<Deque<ChorusRespawnCall>> chorus$respawns=ThreadLocal.withInitial(ArrayDeque::new);
    private static final class ChorusRespawnCall {
        final boolean keepAll;
        MinecraftPlayerTransfer.Handoff handoff;
        ChorusRespawnCall(boolean keepAll){this.keepAll=keepAll;}
    }

    @WrapMethod(method="respawn")
    private ServerPlayer chorus$respawn(ServerPlayer previous,boolean keepAll,Entity.RemovalReason reason,Operation<ServerPlayer> original){
        var call=new ChorusRespawnCall(keepAll);var calls=chorus$respawns.get();calls.addLast(call);
        try{
            var result=original.call(previous,keepAll,reason);
            MinecraftPlayerTransfer.returnedRespawn(call.handoff,result);return result;
        }catch(Throwable failure){
            MinecraftPlayerTransfer.failed(call.handoff,failure);throw failure;
        }finally{
            try{MinecraftPlayerTransfer.end(call.handoff);}
            finally{calls.removeLast();if(calls.isEmpty())chorus$respawns.remove();}
        }
    }

    @WrapOperation(method="respawn",at=@At(value="INVOKE",
            target="Lnet/minecraft/server/level/ServerPlayer;findRespawnPositionAndUseSpawnBlock(ZLnet/minecraft/world/level/portal/TeleportTransition$PostTeleportTransition;)Lnet/minecraft/world/level/portal/TeleportTransition;"))
    private TeleportTransition chorus$destination(ServerPlayer previous,boolean useSpawnBlock,
            TeleportTransition.PostTeleportTransition callback,Operation<TeleportTransition> original){
        var transition=original.call(previous,useSpawnBlock,callback);var call=chorus$respawns.get().getLast();
        call.handoff=MinecraftPlayerTransfer.beginRespawn(previous,transition.newLevel(),call.keepAll);return transition;
    }

    @WrapOperation(method="respawn",at=@At(value="INVOKE",
            target="Lnet/minecraft/server/level/ServerLevel;addRespawnedPlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void chorus$inserted(ServerLevel destination,ServerPlayer replacement,Operation<Void> original){
        var handoff=chorus$respawns.get().getLast().handoff;
        MinecraftPlayerTransfer.prepareRespawn(handoff,replacement,destination);
        original.call(destination,replacement);
        MinecraftPlayerTransfer.insertedRespawn(handoff,replacement,destination);
    }
}
