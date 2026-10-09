package com.imdomestic.chorus.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FabricEquipmentNetworking {
    private FabricEquipmentNetworking() {}
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(EquipmentPayloads.Visit.TYPE, EquipmentPayloads.Visit.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EquipmentPayloads.Request.TYPE, EquipmentPayloads.Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EquipmentPayloads.View.TYPE, EquipmentPayloads.View.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(EquipmentPayloads.Visit.TYPE, (payload, context) -> EquipmentNetworkServer.LIVE.visit(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(EquipmentPayloads.Request.TYPE, (payload, context) -> EquipmentNetworkServer.LIVE.request(context.player(), payload));
    }
}
