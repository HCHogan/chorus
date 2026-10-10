package com.imdomestic.chorus.client;

import com.imdomestic.chorus.network.MovementInputPayload;
import com.imdomestic.chorus.platform.minecraft.MinecraftMovementInput;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;

/** State lives on the actual client entity, so unload/respawn/disconnect cannot retain a stale mask map. */
public final class MovementInputClient {
    private static final Object OWNER=new Object();
    private MovementInputClient() {}
    public static void accept(MovementInputPayload payload){
        var client=Minecraft.getInstance();if(client.level==null||!client.level.dimension().identifier().toString().equals(payload.dimension()))return;
        var entity=client.level.getEntity(payload.entityId());
        if(entity instanceof LivingEntity living&&entity.getUUID().equals(payload.uuid()))((MinecraftMovementInput.Synced)living).chorus$movementProjection(OWNER,payload.mask(),payload.anchor());
    }
}
