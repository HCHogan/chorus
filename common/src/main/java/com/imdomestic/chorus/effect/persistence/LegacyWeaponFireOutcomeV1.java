package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.weapon.WeaponFire;

/** Preserve every name of the original fire outcome enum under its existing storage id. */
public enum LegacyWeaponFireOutcomeV1 {
    ACCEPTED, EMPTY_HANDS, NOT_CONFIGURED, COOLDOWN, CONDITION, NO_AMMUNITION, RESTRICTED;
    public WeaponFire.Outcome current(){return WeaponFire.Outcome.valueOf(name());}
}
