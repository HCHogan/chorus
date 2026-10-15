package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import com.imdomestic.chorus.effect.weapon.WeaponHandling;
import com.imdomestic.chorus.effect.weapon.WeaponHandling.*;

/** Exact accepted phase before explicit domain-clock translation. */
public record LegacyWeaponHandlingPlanV1(String holder, String token, Optional<String> targetSlot, Optional<Loadout.Gear> target,
            String phaseSlot, Loadout.Gear gear, BuffInstance.Origin origin, Phase phase, long startedAt, long dueAt,
            Measure input, Measure duration, Optional<CalculationPipeline.Result> calculation, boolean completed) {
    public LegacyWeaponHandlingPlanV1 { new WeaponHandling.Plan(holder, token, targetSlot, target, phaseSlot, gear, origin, phase, startedAt, dueAt, input, duration, calculation, completed); }
    public WeaponHandling.Plan current() { return new WeaponHandling.Plan(holder, token, targetSlot, target, phaseSlot, gear, origin, phase, startedAt, dueAt, input, duration, calculation, completed); }
}
