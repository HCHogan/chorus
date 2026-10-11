package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.EffectState.Timer;
import com.imdomestic.chorus.effect.EffectState.Mode;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.resource.ResourceState;
import java.util.Map;

/** Exact state shape before accepted weapon handling. Restored sessions begin with no pending switch. */
public record LegacyEffectStateV1(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups, Map<String, com.imdomestic.chorus.effect.resource.RetainedCosts.Entry> retainedCosts, Map<String, com.imdomestic.chorus.effect.combat.DamageTallies.Entry> damageTallies) {
    public LegacyEffectStateV1 { new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies); }
    public EffectState current() { return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies); }
}
