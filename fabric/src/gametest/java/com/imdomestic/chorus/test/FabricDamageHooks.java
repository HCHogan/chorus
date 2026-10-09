package com.imdomestic.chorus.test;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

public final class FabricDamageHooks implements TestDamageHooks.Bridge {
    @Override public void allowDamage(TestDamageHooks.AllowDamage listener) {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(listener::allow);
    }
    @Override public void afterDamage(TestDamageHooks.AfterDamage listener) {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(listener::after);
    }
}
