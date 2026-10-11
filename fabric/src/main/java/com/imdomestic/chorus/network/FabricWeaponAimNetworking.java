package com.imdomestic.chorus.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FabricWeaponAimNetworking {
    private FabricWeaponAimNetworking() {}
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(WeaponAimPayloads.Input.TYPE, WeaponAimPayloads.Input.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WeaponAimPayloads.View.TYPE, WeaponAimPayloads.View.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(WeaponAimPayloads.Input.TYPE, (payload, context) -> WeaponAimNetworkServer.LIVE.request(context.player(), payload));
    }
}
