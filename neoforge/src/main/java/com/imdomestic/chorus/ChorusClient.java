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
        bus.addListener((RegisterClientPayloadHandlersEvent event)->event.register(com.imdomestic.chorus.network.InputTransferPayload.TYPE,(payload,context)->com.imdomestic.chorus.client.InputTransferClient.accept(payload,context.connection())));
        bus.addListener((RegisterClientPayloadHandlersEvent event) -> event.register(com.imdomestic.chorus.network.MovementInputPayload.TYPE, (payload, context) -> com.imdomestic.chorus.client.MovementInputClient.accept(payload)));
        bus.addListener((RegisterClientPayloadHandlersEvent event) -> event.register(com.imdomestic.chorus.network.HorizontalSpeedPayload.TYPE, (payload, context) -> com.imdomestic.chorus.client.HorizontalSpeedClient.accept(payload)));
        bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) -> event.registerEntityRenderer(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_PROJECTILE.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new));
        bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) -> event.registerEntityRenderer(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_ENTITY.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new));
        bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) -> event.registerEntityRenderer(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_CONSTRUCT.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new));
        EquipmentClient.init(ClientPacketDistributor::sendToServer, () -> Minecraft.getInstance().getConnection() != null && Minecraft.getInstance().getConnection().hasChannel(EquipmentPayloads.Visit.TYPE));
        bus.addListener((RegisterClientPayloadHandlersEvent event) -> event.register(EquipmentPayloads.View.TYPE, (view, context) -> EquipmentClient.accept(view)));
        var open = new KeyMapping("key.chorus.equipment", InputConstants.KEY_K, KeyMapping.Category.INVENTORY);
        var catchProjectile = new KeyMapping("key.chorus.catch_projectile", InputConstants.KEY_G, KeyMapping.Category.GAMEPLAY);
        var finisher = new KeyMapping("key.chorus.finisher", InputConstants.KEY_H, KeyMapping.Category.GAMEPLAY);
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(finisher));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> { while (finisher.consumeClick()) com.imdomestic.chorus.client.FinisherInputClient.press(ClientPacketDistributor::sendToServer,
                () -> Minecraft.getInstance().getConnection() != null && Minecraft.getInstance().getConnection().hasChannel(com.imdomestic.chorus.network.FinisherInputPayload.TYPE)); });
        bus.addListener((RegisterClientPayloadHandlersEvent event) -> event.register(com.imdomestic.chorus.network.WeaponChargePayloads.View.TYPE, (view, context) -> com.imdomestic.chorus.client.WeaponChargeClient.accept(view)));
        bus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) -> event.registerAboveAll(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("chorus", "weapon_charge"), com.imdomestic.chorus.client.WeaponChargeHud::render));
        var charge = new KeyMapping("key.chorus.charge", InputConstants.KEY_R, KeyMapping.Category.GAMEPLAY);
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(charge));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> com.imdomestic.chorus.client.WeaponChargeClient.tick(charge, ClientPacketDistributor::sendToServer,
                () -> Minecraft.getInstance().getConnection() != null && Minecraft.getInstance().getConnection().hasChannel(com.imdomestic.chorus.network.WeaponChargePayloads.Input.TYPE)));
        bus.addListener((RegisterClientPayloadHandlersEvent event) -> event.register(com.imdomestic.chorus.network.WeaponAimPayloads.View.TYPE, (view, context) -> com.imdomestic.chorus.client.WeaponAimClient.accept(view)));
        var aim = new KeyMapping("key.chorus.aim", InputConstants.KEY_Z, KeyMapping.Category.GAMEPLAY);
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(aim));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> com.imdomestic.chorus.client.WeaponAimClient.tick(aim, ClientPacketDistributor::sendToServer,
                () -> Minecraft.getInstance().getConnection() != null && Minecraft.getInstance().getConnection().hasChannel(com.imdomestic.chorus.network.WeaponAimPayloads.Input.TYPE)));
        var grenade = new KeyMapping("key.chorus.grenade", InputConstants.KEY_V, KeyMapping.Category.GAMEPLAY);
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(grenade));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> com.imdomestic.chorus.client.AbilityInputClient.tick(grenade, "chorus_d2:grenade", ClientPacketDistributor::sendToServer,
                () -> Minecraft.getInstance().getConnection() != null && Minecraft.getInstance().getConnection().hasChannel(com.imdomestic.chorus.network.AbilityInputPayload.TYPE)));
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(catchProjectile));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> { while (catchProjectile.consumeClick()) com.imdomestic.chorus.client.ProjectileCatchClient.press(ClientPacketDistributor::sendToServer,
                () -> Minecraft.getInstance().getConnection() != null && Minecraft.getInstance().getConnection().hasChannel(com.imdomestic.chorus.network.ProjectileCatchPayload.TYPE)); });
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(open));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> { while (open.consumeClick()) EquipmentClient.open(); });
    }
}
