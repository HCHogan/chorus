package com.imdomestic.chorus;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import com.imdomestic.chorus.platform.NeoForgeRegistrationHelper;

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
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                Greeting.onPlayerJoin(player);
            }
        });
    }
}
