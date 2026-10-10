package com.imdomestic.chorus.client;

import com.imdomestic.chorus.network.HorizontalSpeedPayload;
import com.imdomestic.chorus.platform.minecraft.MinecraftHorizontalSpeed;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;

public final class HorizontalSpeedClient {
    private static final Object OWNER=new Object();
    private HorizontalSpeedClient() {}
    public static void accept(HorizontalSpeedPayload payload){
        var client=Minecraft.getInstance();if(client.level==null||!client.level.dimension().identifier().toString().equals(payload.dimension()))return;
        var entity=client.level.getEntity(payload.entityId());if(entity instanceof LivingEntity actor&&actor.getUUID().equals(payload.uuid()))((MinecraftHorizontalSpeed.Synced)actor).chorus$horizontalSpeed().apply(OWNER,payload.metersPerSecond(),actor);
    }
}
