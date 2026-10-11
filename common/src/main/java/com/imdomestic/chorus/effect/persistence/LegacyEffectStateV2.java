package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.EffectState.Timer;
import com.imdomestic.chorus.effect.EffectState.Mode;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.resource.ResourceState;
import java.util.Map;

/** Exact pre-aim shape. A pending switch survives migration without inventing held input. */
public record LegacyEffectStateV2(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups, Map<String, com.imdomestic.chorus.effect.resource.RetainedCosts.Entry> retainedCosts, Map<String, com.imdomestic.chorus.effect.combat.DamageTallies.Entry> damageTallies, Map<String, com.imdomestic.chorus.effect.weapon.WeaponHandling.Plan> handling) {
    public LegacyEffectStateV2 { currentState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling); }
    private static EffectState currentState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups, Map<String, com.imdomestic.chorus.effect.resource.RetainedCosts.Entry> retainedCosts, Map<String, com.imdomestic.chorus.effect.combat.DamageTallies.Entry> damageTallies, Map<String, com.imdomestic.chorus.effect.weapon.WeaponHandling.Plan> handling) {
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling);
    }
    public EffectState current() { return currentState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling); }
}
