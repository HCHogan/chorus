package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.data.Condition;
import com.imdomestic.chorus.effect.weapon.WeaponCharge;
import com.imdomestic.chorus.effect.weapon.WeaponDefinition;

/** Exact single-cycle charging record. Old full triggers never opt into repeat. */
public record LegacyWeaponChargingV2(WeaponDefinition.Timing minimum, WeaponDefinition.Timing full, Condition condition, WeaponCharge.Trigger trigger) {
    public LegacyWeaponChargingV2 { new WeaponDefinition.Charging(minimum, full, condition, trigger); }
    public WeaponDefinition.Charging current() { return new WeaponDefinition.Charging(minimum, full, condition, trigger); }
}
