package com.imdomestic.chorus.registry;

import com.imdomestic.chorus.platform.Services;
import com.imdomestic.chorus.platform.minecraft.EffectProjectile;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.*;
import java.util.function.Supplier;

public final class ChorusEntities {
    private ChorusEntities() {}
    public static final Supplier<EntityType<EffectProjectile>> EFFECT_PROJECTILE = Services.REGISTRATION.register(Registries.ENTITY_TYPE, "effect_projectile",
            key -> EntityType.Builder.<EffectProjectile>of(EffectProjectile::new, MobCategory.MISC).sized(0.25f, 0.25f).noSave().noSummon().clientTrackingRange(8).updateInterval(1).build(key));
    public static void init() {}
}
