package com.imdomestic.chorus;

import net.fabricmc.api.ClientModInitializer;

public final class ChorusClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_PROJECTILE.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
    }
}
