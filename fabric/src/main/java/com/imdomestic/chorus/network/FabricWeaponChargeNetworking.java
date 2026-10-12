package com.imdomestic.chorus.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FabricWeaponChargeNetworking {
    private FabricWeaponChargeNetworking() {}
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(WeaponChargePayloads.Input.TYPE, WeaponChargePayloads.Input.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WeaponChargePayloads.View.TYPE, WeaponChargePayloads.View.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(WeaponChargePayloads.Input.TYPE, (payload, context) -> WeaponChargeNetworkServer.LIVE.request(context.player(), payload));
    }
}
