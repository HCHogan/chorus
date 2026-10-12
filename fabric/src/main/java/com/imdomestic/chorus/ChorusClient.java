package com.imdomestic.chorus;

import com.imdomestic.chorus.client.EquipmentClient;
import com.imdomestic.chorus.network.EquipmentPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;

public final class ChorusClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(com.imdomestic.chorus.network.MovementInputPayload.TYPE, (payload, context) -> com.imdomestic.chorus.client.MovementInputClient.accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(com.imdomestic.chorus.network.HorizontalSpeedPayload.TYPE, (payload, context) -> com.imdomestic.chorus.client.HorizontalSpeedClient.accept(payload));
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_PROJECTILE.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_ENTITY.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_CONSTRUCT.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        EquipmentClient.init(ClientPlayNetworking::send, () -> ClientPlayNetworking.canSend(EquipmentPayloads.Visit.TYPE));
        ClientPlayNetworking.registerGlobalReceiver(EquipmentPayloads.View.TYPE, (view, context) -> EquipmentClient.accept(view));
        var open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.chorus.equipment", InputConstants.KEY_K, KeyMapping.Category.INVENTORY));
        var catchProjectile = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.chorus.catch_projectile", InputConstants.KEY_G, KeyMapping.Category.GAMEPLAY));
        var finisher = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.chorus.finisher", InputConstants.KEY_H, KeyMapping.Category.GAMEPLAY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> { while (finisher.consumeClick()) com.imdomestic.chorus.client.FinisherInputClient.press(ClientPlayNetworking::send,
                () -> ClientPlayNetworking.canSend(com.imdomestic.chorus.network.FinisherInputPayload.TYPE)); });
        ClientPlayNetworking.registerGlobalReceiver(com.imdomestic.chorus.network.WeaponChargePayloads.View.TYPE, (view, context) -> com.imdomestic.chorus.client.WeaponChargeClient.accept(view));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CROSSHAIR,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("chorus", "weapon_charge"), com.imdomestic.chorus.client.WeaponChargeHud::render);
        var charge = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.chorus.charge", InputConstants.KEY_R, KeyMapping.Category.GAMEPLAY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> com.imdomestic.chorus.client.WeaponChargeClient.tick(charge, ClientPlayNetworking::send,
                () -> ClientPlayNetworking.canSend(com.imdomestic.chorus.network.WeaponChargePayloads.Input.TYPE)));
        ClientPlayNetworking.registerGlobalReceiver(com.imdomestic.chorus.network.WeaponAimPayloads.View.TYPE, (view, context) -> com.imdomestic.chorus.client.WeaponAimClient.accept(view));
        var aim = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.chorus.aim", InputConstants.KEY_Z, KeyMapping.Category.GAMEPLAY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> com.imdomestic.chorus.client.WeaponAimClient.tick(aim, ClientPlayNetworking::send,
                () -> ClientPlayNetworking.canSend(com.imdomestic.chorus.network.WeaponAimPayloads.Input.TYPE)));
        var grenade = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.chorus.grenade", InputConstants.KEY_V, KeyMapping.Category.GAMEPLAY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> com.imdomestic.chorus.client.AbilityInputClient.tick(grenade, "chorus_d2:grenade", ClientPlayNetworking::send,
                () -> ClientPlayNetworking.canSend(com.imdomestic.chorus.network.AbilityInputPayload.TYPE)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> { while (catchProjectile.consumeClick()) com.imdomestic.chorus.client.ProjectileCatchClient.press(ClientPlayNetworking::send,
                () -> ClientPlayNetworking.canSend(com.imdomestic.chorus.network.ProjectileCatchPayload.TYPE)); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> { while (open.consumeClick()) EquipmentClient.open(); });
    }
}
