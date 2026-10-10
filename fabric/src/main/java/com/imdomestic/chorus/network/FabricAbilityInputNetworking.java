package com.imdomestic.chorus.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FabricAbilityInputNetworking {
    private FabricAbilityInputNetworking() {}
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(AbilityInputPayload.TYPE, AbilityInputPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AbilityInputPayload.TYPE, (payload, context) -> AbilityInputNetworkServer.LIVE.request(context.player(), payload));
    }
}
