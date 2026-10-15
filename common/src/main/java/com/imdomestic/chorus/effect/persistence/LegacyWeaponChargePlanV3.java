package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.target.EntityAnchor;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import com.imdomestic.chorus.effect.weapon.WeaponCharge;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.effect.weapon.WeaponCharge.*;

/** Exact accepted phase before explicit domain-clock translation. */
public record LegacyWeaponChargePlanV3(String holder,String token,String slot,Loadout.Gear gear,BuffInstance.Origin origin,Phase phase,
            long startedAt,long fullAt,Timing minimum,Timing full,Optional<EntityAnchor.Reference> holderReference,Trigger trigger,
            boolean repeat,long cycle,Optional<WeaponFire.Shot> recovery) {
    public LegacyWeaponChargePlanV3 { new WeaponCharge.Plan(holder, token, slot, gear, origin, phase, startedAt, fullAt, minimum, full, holderReference, trigger, repeat, cycle, recovery); }
    public WeaponCharge.Plan current() { return new WeaponCharge.Plan(holder, token, slot, gear, origin, phase, startedAt, fullAt, minimum, full, holderReference, trigger, repeat, cycle, recovery); }
}
