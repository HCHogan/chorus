package com.imdomestic.chorus.network;

import com.imdomestic.chorus.platform.Services;
import com.imdomestic.chorus.platform.minecraft.MinecraftMovementInput;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;

/** No subscription cache: native chunk viewers receive changes, pairing/load sends a current snapshot. */
public final class MovementInputNetwork {
    private MovementInputNetwork() {}
    private static MovementInputPayload payload(LivingEntity actor){return new MovementInputPayload(actor.level().dimension().identifier().toString(),actor.getId(),actor.getUUID(),((MinecraftMovementInput.Synced)actor).chorus$movementRestrictions());}
    public static void changed(LivingEntity actor){
        if(!(actor.level() instanceof ServerLevel level))return;
        var payload=payload(actor);
        for(var viewer:level.getChunkSource().chunkMap.getPlayers(actor.chunkPosition(),false))if(viewer!=actor)Services.PLATFORM.sendMovementInput(viewer,payload);
        if(actor instanceof ServerPlayer player)Services.PLATFORM.sendMovementInput(player,payload);
    }
    public static void sync(LivingEntity actor,ServerPlayer viewer){
        if(actor.level()==viewer.level()&&!actor.isRemoved())Services.PLATFORM.sendMovementInput(viewer,payload(actor));
    }
}
