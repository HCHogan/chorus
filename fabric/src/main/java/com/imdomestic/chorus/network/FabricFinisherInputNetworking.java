package com.imdomestic.chorus.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FabricFinisherInputNetworking {
    private FabricFinisherInputNetworking() {}
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(FinisherInputPayload.TYPE, FinisherInputPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(FinisherInputPayload.TYPE, (payload, context) -> FinisherInputNetworkServer.LIVE.request(context.player(), payload));
    }
}
