package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ammo.Ammunition;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.math.*;
import java.util.*;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.effect.weapon.WeaponFire.*;

/** Exact accepted phase before explicit domain-clock translation. */
public record LegacyWeaponFireShotV1(String holder, String token, Loadout.Gear gear, BuffInstance.Origin origin,
            long firedAt, long readyAt, Measure input, Measure interval, Optional<CalculationProfile.Result> calculation, Ammunition.Result cost) {
    public LegacyWeaponFireShotV1 { new WeaponFire.Shot(holder, token, gear, origin, firedAt, readyAt, input, interval, calculation, cost); }
    public WeaponFire.Shot current() { return new WeaponFire.Shot(holder, token, gear, origin, firedAt, readyAt, input, interval, calculation, cost); }
}
