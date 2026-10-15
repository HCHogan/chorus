package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import com.imdomestic.chorus.effect.weapon.WeaponAim;
import com.imdomestic.chorus.effect.weapon.WeaponAim.*;

/** Exact accepted phase before explicit domain-clock translation. */
public record LegacyWeaponAimPlanV1(String holder,String token,String slot,Loadout.Gear gear,BuffInstance.Origin origin,Phase phase,
            long startedAt,long dueAt,Measure input,Measure duration,Optional<CalculationPipeline.Result> calculation) {
    public LegacyWeaponAimPlanV1 { new WeaponAim.Plan(holder, token, slot, gear, origin, phase, startedAt, dueAt, input, duration, calculation); }
    public WeaponAim.Plan current() { return new WeaponAim.Plan(holder, token, slot, gear, origin, phase, startedAt, dueAt, input, duration, calculation); }
}
