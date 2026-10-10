package com.imdomestic.chorus.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FabricProjectileCatchNetworking {
    private FabricProjectileCatchNetworking() {}
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(ProjectileCatchPayload.TYPE, ProjectileCatchPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ProjectileCatchPayload.TYPE, (payload, context) -> ProjectileCatchNetworkServer.LIVE.request(context.player(), payload));
    }
}
