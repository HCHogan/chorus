package com.imdomestic.chorus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public final class ChorusClient {
    public ChorusClient(IEventBus bus) {
        bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) -> event.registerEntityRenderer(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_PROJECTILE.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new));
    }
}
