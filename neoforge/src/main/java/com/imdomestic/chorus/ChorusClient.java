package com.imdomestic.chorus;

import com.imdomestic.chorus.client.EquipmentClient;
import com.imdomestic.chorus.network.EquipmentPayloads;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import com.mojang.blaze3d.platform.InputConstants;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public final class ChorusClient {
    public ChorusClient(IEventBus bus) {
        bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) -> event.registerEntityRenderer(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_PROJECTILE.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new));
        bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) -> event.registerEntityRenderer(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_ENTITY.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new));
        EquipmentClient.init(ClientPacketDistributor::sendToServer, () -> Minecraft.getInstance().getConnection() != null && Minecraft.getInstance().getConnection().hasChannel(EquipmentPayloads.Visit.TYPE));
        bus.addListener((RegisterClientPayloadHandlersEvent event) -> event.register(EquipmentPayloads.View.TYPE, (view, context) -> EquipmentClient.accept(view)));
        var open = new KeyMapping("key.chorus.equipment", InputConstants.KEY_K, KeyMapping.Category.INVENTORY);
        var catchProjectile = new KeyMapping("key.chorus.catch_projectile", InputConstants.KEY_G, KeyMapping.Category.GAMEPLAY);
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(catchProjectile));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> { while (catchProjectile.consumeClick()) com.imdomestic.chorus.client.ProjectileCatchClient.press(ClientPacketDistributor::sendToServer,
                () -> Minecraft.getInstance().getConnection() != null && Minecraft.getInstance().getConnection().hasChannel(com.imdomestic.chorus.network.ProjectileCatchPayload.TYPE)); });
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(open));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> { while (open.consumeClick()) EquipmentClient.open(); });
    }
}
