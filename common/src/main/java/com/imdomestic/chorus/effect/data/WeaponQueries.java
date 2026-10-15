package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ammo.AmmoCapacity;
import com.imdomestic.chorus.effect.equipment.EquipmentChange;
import com.imdomestic.chorus.effect.equipment.CompiledEquipment;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.resource.ResourceReadView;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** One pure weapon transaction's resource input; local proposals may change gear/ammo, never resource balances/time. */
final class WeaponQueries {
    private final CompiledEffects program;
    private final Optional<ResourceReadView> resources;
    WeaponQueries(CompiledEffects program, EffectState state, Optional<ResourceReadView> resources) {
        program.requireWorldQueryView(state, resources); this.program = program; this.resources = resources;
    }
    private Optional<ResourceReadView> view(EffectState state) { return resources.map(value -> value.withLocalState(state)); }
    CompiledEquipment equipment() { return program.equipment(); }
    RuleEngine.Local<EffectState> changeEquipment(EffectState state, EquipmentChange change) {
        return program.changeEquipment(state, change, view(state));
    }
    Evaluation evaluation(EffectState state, RuleEngine.Context context, Map<String, ResultShape> results) {
        return program.evaluation(state, context, results, view(state));
    }
    ActionGate.Decision checkAction(EffectState state, ActionGate.Kind action, ActionGate.Phase phase, EffectEvent input) {
        return program.checkAction(state, action, phase, input, view(state));
    }
    AmmoCapacity.View ammoCapacity(EffectState state, String weapon) {
        var account = state.ammunition().get(weapon);
        if (account == null) throw new IllegalArgumentException("Missing ammunition account: " + weapon);
        return program.ammoCapacity(state, account, NumericQuery.Path.empty(), view(state));
    }
    CalculationProfile.Result calculate(EffectState state, String holder, EffectEvent query, String profile, Measure base,
            List<NumericContribution> captured) {
        return program.calculate(state, holder, query, profile, base, captured, view(state));
    }
    CalculationPipeline.Result calculatePipeline(EffectState state, String holder, EffectEvent query, List<String> profiles, Measure base) {
        return program.calculatePipeline(state, holder, query, profiles, base, view(state));
    }
}
