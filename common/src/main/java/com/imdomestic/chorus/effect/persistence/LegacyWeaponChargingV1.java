package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.data.Condition;
import com.imdomestic.chorus.effect.weapon.WeaponDefinition;

/** Exact release-only charging definition. Old data must never opt into autonomous firing. */
public record LegacyWeaponChargingV1(WeaponDefinition.Timing minimum, WeaponDefinition.Timing full, Condition condition) {
    public LegacyWeaponChargingV1 { new WeaponDefinition.Charging(minimum, full, condition); }
    public WeaponDefinition.Charging current() { return new WeaponDefinition.Charging(minimum, full, condition); }
}
