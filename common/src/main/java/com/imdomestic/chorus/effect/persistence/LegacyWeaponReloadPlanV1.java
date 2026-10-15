package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.math.*;
import java.util.*;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.imdomestic.chorus.effect.weapon.WeaponReload.*;

/** Exact accepted phase before explicit domain-clock translation. */
public record LegacyWeaponReloadPlanV1(String holder, String token, Loadout.Gear gear, BuffInstance.Origin origin,
            long startedAt, long dueAt, Measure input, Measure duration, Optional<CalculationPipeline.Result> calculation,
            Optional<Portion> portion, int step, Phase phase) {
    public LegacyWeaponReloadPlanV1 { new WeaponReload.Plan(holder, token, gear, origin, startedAt, dueAt, input, duration, calculation, portion, step, phase); }
    public WeaponReload.Plan current() { return new WeaponReload.Plan(holder, token, gear, origin, startedAt, dueAt, input, duration, calculation, portion, step, phase); }
}
