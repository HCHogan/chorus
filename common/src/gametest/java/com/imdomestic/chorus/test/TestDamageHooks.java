package com.imdomestic.chorus.test;

import java.util.ServiceLoader;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Test-only injection of cancellation/reentry at each loader's native damage callbacks. */
public final class TestDamageHooks {
    private TestDamageHooks() {}
    @FunctionalInterface public interface AllowDamage { boolean allow(LivingEntity entity, DamageSource source, float amount); }
    @FunctionalInterface public interface AfterDamage { void after(LivingEntity entity, DamageSource source, float original, float inflicted, boolean blocked); }
    @FunctionalInterface public interface Registrar<T> { void register(T listener); }
    public interface Bridge {
        void allowDamage(AllowDamage listener);
        void afterDamage(AfterDamage listener);
    }
    private static final Bridge BRIDGE = ServiceLoader.load(Bridge.class).findFirst().orElseThrow();
    public static final Registrar<AllowDamage> ALLOW_DAMAGE = BRIDGE::allowDamage;
    public static final Registrar<AfterDamage> AFTER_DAMAGE = BRIDGE::afterDamage;
}
