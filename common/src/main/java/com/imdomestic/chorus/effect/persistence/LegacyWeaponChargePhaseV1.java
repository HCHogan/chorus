package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.weapon.WeaponCharge;

/** Preserve the exact original phase enumeration. */
public enum LegacyWeaponChargePhaseV1 {
    CHARGING, READY;
    public WeaponCharge.Phase current() { return WeaponCharge.Phase.valueOf(name()); }
}
