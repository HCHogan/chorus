package com.imdomestic.chorus.network;

import com.imdomestic.chorus.platform.Services;
import com.imdomestic.chorus.platform.minecraft.MinecraftHorizontalSpeed;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;

public final class HorizontalSpeedNetwork {
    private HorizontalSpeedNetwork() {}
    private static HorizontalSpeedPayload payload(LivingEntity actor){return new HorizontalSpeedPayload(actor.level().dimension().identifier().toString(),actor.getId(),actor.getUUID(),MinecraftHorizontalSpeed.speed(actor));}
    public static void changed(LivingEntity actor){
        if(!(actor.level() instanceof ServerLevel level))return;var payload=payload(actor);
        for(var viewer:level.getChunkSource().chunkMap.getPlayers(actor.chunkPosition(),false))if(viewer!=actor)Services.PLATFORM.sendHorizontalSpeed(viewer,payload);
        if(actor instanceof ServerPlayer player)Services.PLATFORM.sendHorizontalSpeed(player,payload);
    }
    public static void sync(LivingEntity actor,ServerPlayer viewer){if(actor.level()==viewer.level()&&!actor.isRemoved())Services.PLATFORM.sendHorizontalSpeed(viewer,payload(actor));}
}
