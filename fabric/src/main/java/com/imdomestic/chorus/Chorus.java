package com.imdomestic.chorus;

import net.fabricmc.api.ModInitializer;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class Chorus implements ModInitializer {

    @Override
    public void onInitialize() {

        // This method is invoked by the Fabric mod loader when it is ready
        // to load your mod. You can access Fabric and Common code in this
        // project.

        // Use Fabric to bootstrap the Common mod.
        Constants.LOG.info("Hello Fabric world!");
        CommonClass.init();
        com.imdomestic.chorus.network.FabricEquipmentNetworking.init();
        com.imdomestic.chorus.network.FabricProjectileCatchNetworking.init();
        ServerLifecycleEvents.SERVER_STOPPED.register(com.imdomestic.chorus.network.EquipmentNetworkServer.LIVE::stop);
        ServerLifecycleEvents.SERVER_STOPPED.register(com.imdomestic.chorus.network.ProjectileCatchNetworkServer.LIVE::stop);
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> com.imdomestic.chorus.network.ProjectileCatchNetworkServer.LIVE.disconnected(listener));
        net.fabricmc.fabric.api.event.registry.DynamicRegistries.registerReloadable(
                com.imdomestic.chorus.platform.minecraft.EffectPrograms.KEY, com.imdomestic.chorus.platform.minecraft.LoadedProgram.CODEC);
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) ->
                com.imdomestic.chorus.platform.minecraft.EffectCommands.register(dispatcher));
        ServerTickEvents.END_LEVEL_TICK.register(MinecraftEffectRuntime::tick);
        ServerTickEvents.END_LEVEL_TICK.register(com.imdomestic.chorus.network.EquipmentNetworkServer.LIVE::tick);
        ServerLevelEvents.UNLOAD.register((server, level) -> MinecraftEffectRuntime.unload(level));
        ServerLifecycleEvents.SERVER_STOPPED.register(MinecraftEffectRuntime::stop);
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> Greeting.onPlayerJoin(listener.player));
    }
}
