package com.imdomestic.chorus.test;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

public final class NeoForgeDamageHooks implements TestDamageHooks.Bridge {
    @Override public void allowDamage(TestDamageHooks.AllowDamage listener) {
        NeoForge.EVENT_BUS.addListener((LivingIncomingDamageEvent event) -> {
            if (!listener.allow(event.getEntity(), event.getSource(), event.getAmount())) event.setCanceled(true);
        });
    }
    @Override public void afterDamage(TestDamageHooks.AfterDamage listener) {
        NeoForge.EVENT_BUS.addListener((LivingDamageEvent.Post event) -> listener.after(event.getEntity(), event.getSource(),
                event.getOriginalDamage(), event.getInflictedDamage(), event.getBlockedDamage() > 0));
    }
}
