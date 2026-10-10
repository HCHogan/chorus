package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityEnergyObservationTest {
    static final EnergyActions.ObserveAbility ACTION = new EnergyActions.ObserveAbility(PugilistTest.SLOT, Evaluation.Target.VICTIM);
    static final ResultShape SHAPE = ACTION.validate(new Validation(Map.of(), Map.of(), false));
    static RuleEngine.Local<EffectState> observe(PugilistTest.Harness h, EffectState state, String holder) {
        return (RuleEngine.Local<EffectState>) ACTION.execute(AbilityEnergyTest.evaluation(h, state, holder));
    }
    @Test void missingSelectionAndNoCostStayDistinctFromAnEmptyAccount() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle");
        for (String selection : Arrays.asList(null, "test:no_resource")) {
            h.select(selection); var before = h.state(); var out = observe(h, before, "player");
            assertSame(before, out.state()); assertTrue(out.emitted().isEmpty());
            assertTrue(SHAPE.flag(selection == null ? "no_selection" : "no_resource", out.result()));
            assertFalse(SHAPE.flag("available", out.result())); assertFalse(SHAPE.flag("full", out.result()));
            for (String field : SHAPE.fields().keySet()) assertThrows(IllegalArgumentException.class, () -> SHAPE.read(field, out.result()));
        }
        h.select("test:alternate"); var r = observe(h, h.state(), "player").result();
        assertTrue(SHAPE.flag("available", r)); assertEquals(0, SHAPE.read("value", r).value());
    }
    @Test void chargeBoundariesAndSnapshotSurviveLaterGains() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle"); h.select("test:alternate");
        var key = new ResourceState.Key("player", "test:alternate_energy");
        for (double value : new double[]{0, .999, 1, 1.5, 2}) {
            var state = h.state().withResource(new ResourceState(key, value, 2, 0)); var out = observe(h, state, "player");
            var r = (EnergyActions.AbilityObservation) out.result();
            assertSame(state, out.state()); assertTrue(out.emitted().isEmpty()); assertEquals(key, r.observed().key());
            assertEquals(value, SHAPE.read("value", r).value()); assertEquals(2, SHAPE.read("capacity", r).value());
            assertEquals(2 - value, SHAPE.read("missing", r).value()); assertEquals(Math.floor(value), SHAPE.read("full_charges", r).value());
            assertEquals(value == 2, SHAPE.flag("full", r));
            var gain = AbilityEnergyTest.action(EnergyGains.Basis.FIXED, new Value.Constant(2, Unit.CHARGE), Map.of());
            assertEquals(2, AbilityEnergyTest.execute(h, state, "player", gain).state().resources().get(key).value());
            assertEquals(value, SHAPE.read("value", r).value(), "observation must not follow later account writes");
        }
        assertEquals(Unit.CHARGE, SHAPE.unit("missing")); assertEquals(Unit.COUNT, SHAPE.unit("full_charges"));
    }
    @Test void recipientAndBaseSelectionDetermineObservedAccountEvenWithCastReplacement() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle");
        h.session.start(0, SourceChange.bind(new EffectSource("override", "test:gain_replacement", "player", h.a.origin(), Set.of())));
        var state = h.program.changeAbilities(h.state(), new AbilityChange("ally", AbilityLoadout.EMPTY,
                new AbilityLoadout(Map.of(PugilistTest.SLOT, "test:alternate")))).state();
        var ally = (EnergyActions.AbilityObservation) observe(h, state, "ally").result();
        assertEquals("ally", ally.holder()); assertEquals("ally", ally.observed().key().holder()); assertEquals(Optional.of("test:alternate"), ally.ability());
        var player = (EnergyActions.AbilityObservation) observe(h, state, "player").result();
        assertEquals(Optional.of("chorus_d2:threaded_spike"), player.ability()); assertEquals(PugilistTest.SPIKE, player.observed().key().resource());
    }
    @Test void brokenAccountsFailButReadingNeedsNeitherCostPaymentNorGainProfile() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle"); h.select("test:missing_gain");
        assertTrue(SHAPE.flag("available", observe(h, h.state(), "player").result()), "zero-cost account without gain_profile is observable");
        var missing = EffectState.empty().withSource(h.a).withAbilities("player", new AbilityLoadout(Map.of(PugilistTest.SLOT, "test:alternate")));
        assertThrows(IllegalArgumentException.class, () -> observe(h, missing, "player"));
        var wrongCapacity = missing.withResource(new ResourceState(new ResourceState.Key("player", "test:alternate_energy"), 0, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> observe(h, wrongCapacity, "player"));
        var stale = missing.withAbilities("player", new AbilityLoadout(Map.of(PugilistTest.SLOT, "test:unknown")));
        assertThrows(IllegalArgumentException.class, () -> observe(h, stale, "player"));
    }
    @Test void codecDefaultsTargetAndRejectsUnknownFieldsAndBadSlots() {
        var json = JsonParser.parseString("{\"type\":\"chorus:observe_ability_energy\",\"slot\":\"chorus_d2:melee\"}").getAsJsonObject();
        var action = EffectCodecs.ACTION.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(new EnergyActions.ObserveAbility(PugilistTest.SLOT, Evaluation.Target.SELF), action);
        assertEquals(action, EffectCodecs.ACTION.parse(JsonOps.INSTANCE, EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE, action).getOrThrow()).getOrThrow());
        json.addProperty("resource", "test:wrong"); assertTrue(EffectCodecs.ACTION.parse(JsonOps.INSTANCE, json).error().isPresent());
        assertThrows(IllegalArgumentException.class, () -> new EnergyActions.ObserveAbility("bad slot", Evaluation.Target.SELF));
    }
}
