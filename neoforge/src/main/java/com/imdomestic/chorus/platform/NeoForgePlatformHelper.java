package com.imdomestic.chorus.platform;

import com.imdomestic.chorus.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public class NeoForgePlatformHelper implements IPlatformHelper {
    @Override public void sendEquipmentView(net.minecraft.server.level.ServerPlayer player, com.imdomestic.chorus.network.EquipmentPayloads.View view) {
        if (player.connection.hasChannel(com.imdomestic.chorus.network.EquipmentPayloads.View.TYPE))
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, view);
    }

    @Override
    public net.minecraft.world.entity.item.ItemEntity dropEquipmentOnDeath(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
        // CommonHooks.onPlayerTossEvent starts its own capture and would erase the surrounding death capture.
        return player.dropWithoutEvent(stack, true, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    @Override
    public String getPlatformName() {

        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return !FMLLoader.getCurrent().isProduction();
    }
}
