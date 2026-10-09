package com.imdomestic.chorus.platform;

import com.imdomestic.chorus.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

public class FabricPlatformHelper implements IPlatformHelper {
    @Override public void sendEquipmentView(net.minecraft.server.level.ServerPlayer player, com.imdomestic.chorus.network.EquipmentPayloads.View view) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, com.imdomestic.chorus.network.EquipmentPayloads.View.TYPE))
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, view);
    }

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
