package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AbilityEnergyTest {
    static Evaluation evaluation(PugilistTest.Harness h, EffectState state, String target) {
        return new Evaluation(state, new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), state.buffs().timeMicros(),
                new RuleEngine.Signal("test:gain", new EffectEvent("player", target, h.a.origin(), Set.of(), Map.of()))), "gain", h.a, Map.of()), Map.of(), Map.of(),
                h.program.program().resources().stream().collect(Collectors.toMap(ResourceDefinition::id, x -> x)), Map.of(), Optional.of(h.program));
    }
    static EnergyActions.GrantAbility action(EnergyGains.Basis basis, Value amount, Map<String, Value> factors) {
        return new EnergyActions.GrantAbility(PugilistTest.SLOT, Evaluation.Target.VICTIM, amount, basis, factors, Set.of(), Map.of());
    }
    static RuleEngine.Local<EffectState> execute(PugilistTest.Harness h, EffectState state, String target, EnergyActions.GrantAbility action) {
        return (RuleEngine.Local<EffectState>) action.execute(evaluation(h, state, target));
    }
    @Test void missingSelectionAndCostHaveDistinctOutcomesAndNeverInventNumericResults() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle");
        var action = action(EnergyGains.Basis.BASE, new Value.EventNumber("missing", Unit.CHARGE), Map.of());
        var shape = action.validate(new Validation(Map.of(), Map.of(), false));
        for (String selection : Arrays.asList(null, "test:no_resource")) {
            h.select(selection); var before = h.state(); var outcome = execute(h, before, "player", action);
            var result = (EnergyActions.AbilityResult) outcome.result();
            assertSame(before, outcome.state()); assertTrue(outcome.emitted().isEmpty()); assertFalse(shape.flag("granted", result));
            assertTrue(shape.flag(selection == null ? "no_selection" : "no_resource", result));
            assertThrows(IllegalArgumentException.class, () -> shape.read("credited", result));
        }
    }
    @Test void selectedPoolBelongsToTheActualRecipientAndCastOverridesDoNotRedirectExternalGains() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle"); h.stat(100);
        var state = h.program.changeAbilities(h.state(), new AbilityChange("ally", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of(PugilistTest.SLOT, "test:alternate")))).state();
        var action = action(EnergyGains.Basis.BASE, new Value.Constant(.04, Unit.CHARGE), Map.of());
        var outcome = execute(h, state, "ally", action); var result = (EnergyActions.AbilityResult) outcome.result();
        assertEquals("ally", result.holder()); assertEquals(Optional.of("test:alternate"), result.ability());
        assertEquals(.02, result.granted().grant().credited(), 1e-12); assertEquals(0, outcome.state().resources().get(new ResourceState.Key("player", PugilistTest.SPIKE)).value());
        h.session.start(0, SourceChange.bind(new EffectSource("override", "test:gain_replacement", "player", h.a.origin(), Set.of())));
        result = (EnergyActions.AbilityResult) execute(h, h.state(), "player", action).result();
        assertEquals(Optional.of("chorus_d2:threaded_spike"), result.ability()); assertEquals(.072, result.granted().grant().credited(), 1e-12);
    }
    @Test void referenceAndFixedGainsReuseTheSameTraceAndOverflowContract() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle"); h.select("test:alternate");
        var ref = action(EnergyGains.Basis.REFERENCE, new Value.Constant(.08, Unit.CHARGE), Map.of("test:reference", new Value.Constant(2, Unit.MULTIPLIER)));
        var r = (EnergyActions.AbilityResult) execute(h, h.state(), "player", ref).result();
        assertEquals(.04, r.granted().normalized().base()); assertEquals(.02, r.granted().grant().credited()); assertTrue(r.granted().calculation().isPresent());
        var fixed = action(EnergyGains.Basis.FIXED, new Value.Constant(3, Unit.CHARGE), Map.of());
        r = (EnergyActions.AbilityResult) execute(h, h.state(), "player", fixed).result();
        assertEquals(2, r.granted().grant().credited()); assertEquals(1, r.granted().grant().overflow()); assertTrue(r.granted().calculation().isEmpty());
    }
    @Test void brokenSelectedAccountOrMissingProfileFailsBeforeAnyGainRatherThanLookingLikeNoSelection() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle"); var gain = action(EnergyGains.Basis.BASE, new Value.Constant(.04, Unit.CHARGE), Map.of());
        h.select("test:missing_gain"); var before = h.state();
        assertThrows(IllegalArgumentException.class, () -> execute(h, before, "player", gain)); assertEquals(0, h.energy("test:missing_gain"));
        var missing = EffectState.empty().withSource(h.a).withAbilities("player", new AbilityLoadout(Map.of(PugilistTest.SLOT, "test:alternate")));
        assertThrows(IllegalArgumentException.class, () -> execute(h, missing, "player", gain));
        var stale = missing.withAbilities("player", new AbilityLoadout(Map.of(PugilistTest.SLOT, "test:unknown")));
        assertThrows(IllegalArgumentException.class, () -> execute(h, stale, "player", gain));
        // A cost of zero still identifies an account; it is not the same as an absent cost.
        var fixed = (EnergyActions.AbilityResult) execute(h, before, "player", action(EnergyGains.Basis.FIXED, new Value.Constant(.4, Unit.CHARGE), Map.of())).result();
        assertEquals(.4, fixed.granted().grant().credited());
    }
}
