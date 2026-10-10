package com.imdomestic.chorus;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import com.imdomestic.chorus.platform.NeoForgeRegistrationHelper;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

@Mod(Constants.MOD_ID)
public class Chorus {

    public Chorus(IEventBus eventBus) {

        // This method is invoked by the NeoForge mod loader when it is ready
        // to load your mod. You can access NeoForge and Common code in this
        // project.

        // Use NeoForge to bootstrap the Common mod.
        Constants.LOG.info("Hello NeoForge world!");
        NeoForgeRegistrationHelper.setModBus(eventBus);
        CommonClass.init();
        eventBus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar("1").optional();
            registrar.playToClient(com.imdomestic.chorus.network.MovementInputPayload.TYPE, com.imdomestic.chorus.network.MovementInputPayload.CODEC);
            registrar.playToServer(com.imdomestic.chorus.network.ProjectileCatchPayload.TYPE, com.imdomestic.chorus.network.ProjectileCatchPayload.CODEC,
                    (payload, context) -> com.imdomestic.chorus.network.ProjectileCatchNetworkServer.LIVE.request((ServerPlayer) context.player(), payload));
            registrar.playToServer(com.imdomestic.chorus.network.EquipmentPayloads.Visit.TYPE, com.imdomestic.chorus.network.EquipmentPayloads.Visit.CODEC,
                    (payload, context) -> com.imdomestic.chorus.network.EquipmentNetworkServer.LIVE.visit((ServerPlayer) context.player(), payload));
            registrar.playToServer(com.imdomestic.chorus.network.EquipmentPayloads.Request.TYPE, com.imdomestic.chorus.network.EquipmentPayloads.Request.CODEC,
                    (payload, context) -> com.imdomestic.chorus.network.EquipmentNetworkServer.LIVE.request((ServerPlayer) context.player(), payload));
            registrar.playToClient(com.imdomestic.chorus.network.EquipmentPayloads.View.TYPE, com.imdomestic.chorus.network.EquipmentPayloads.View.CODEC);
        });
        eventBus.addListener((net.neoforged.neoforge.registries.NewDatapackRegistryEvent event) -> event.reloadableRegistry(
                com.imdomestic.chorus.platform.minecraft.EffectPrograms.KEY, com.imdomestic.chorus.platform.minecraft.LoadedProgram.CODEC));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                com.imdomestic.chorus.platform.minecraft.EffectCommands.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> {
            if (event.getLevel() instanceof ServerLevel level) { MinecraftEffectRuntime.tick(level); com.imdomestic.chorus.network.EquipmentNetworkServer.LIVE.tick(level); }
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) MinecraftEffectRuntime.unload(level);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> MinecraftEffectRuntime.stop(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> com.imdomestic.chorus.network.EquipmentNetworkServer.LIVE.stop(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> com.imdomestic.chorus.network.ProjectileCatchNetworkServer.LIVE.stop(event.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) com.imdomestic.chorus.network.ProjectileCatchNetworkServer.LIVE.disconnected(player.connection);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                Greeting.onPlayerJoin(player);
            }
        });
    }
}
