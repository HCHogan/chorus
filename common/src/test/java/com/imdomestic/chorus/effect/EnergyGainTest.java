package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.imdomestic.chorus.stat.codec.StatCodecs;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EnergyGainTest {
    static final String ENERGY = "chorus_d2:threaded_spike_energy";
    static final Unit STAT = new Unit("chorus:stat_point");
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("grantor", "producer", "weapon", "");
    static final EffectSource SOURCE = new EffectSource("input", "test:spike_energy", "grantor", ORIGIN, Set.of());
    static CompiledEffects program() throws Exception { return link("threaded_spike", "strand_defense", "continuity", "threaded_spike_energy", "character_stats", "spike_energy_inputs"); }
    static EffectEvent event(String target, Map<String, Measure> numbers) { return new EffectEvent("grantor", target, ORIGIN, Set.of("chorus_d2:class_mod_energy"), numbers); }
    static final class Harness {
        final CompiledEffects program; final EffectSession session; final ResourceDefinition resource;
        Harness() throws Exception {
            program = program();
            resource = EffectCodecs.RESOURCE.parse(JsonOps.INSTANCE, json("threaded_spike").getAsJsonArray("resources").get(0)).getOrThrow();
            var state = EffectState.empty().withSource(SOURCE).withResource(new ResourceState(new ResourceState.Key("recipient", ENERGY), 0, 1, 0))
                    .withResource(new ResourceState(new ResourceState.Key("grantor", ENERGY), 0, 1, 0));
            session = new EffectSession(engine(program), state, _ -> RuleEngine.Empty.INSTANCE);
        }
        EffectState state() { return session.state().engine().domain(); }
        void stat(String target, double value, long at) { session.start(at, new RuleEngine.Signal("test:stat", event(target, Map.of("stat", new Measure(value, STAT))))); }
        Evaluation evaluation() { return new Evaluation(state(), new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), state().buffs().timeMicros(),
                new RuleEngine.Signal("test:grant", event("recipient", Map.of()))), "query", SOURCE, Map.of()), Map.of(), Map.of(), Map.of(ENERGY, resource), Map.of(), Optional.of(program)); }
        RuleEngine.Local<EffectState> grant(double amount, EnergyGains.Basis basis, Map<String, Value> included) {
            return (RuleEngine.Local<EffectState>) new EnergyActions.Grant(ENERGY, Evaluation.Target.VICTIM, new Value.Constant(amount, Unit.CHARGE), basis, included, Set.of(), Map.of()).execute(evaluation());
        }
    }
    @Test void referenceBasisRemovesOnlyDeclaredFactorsAndKeepsOriginalRequest() {
        var factors = new HashMap<>(Map.of("test:stat", 2.25, "test:ces", .8));
        var n = EnergyGains.normalize(EnergyGains.Basis.REFERENCE, .072, factors); factors.clear();
        assertEquals(.04, n.base()); assertEquals(.072, n.requested()); assertEquals(2, n.referenceFactors().size());
        assertThrows(UnsupportedOperationException.class, () -> n.referenceFactors().clear());
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> EnergyGains.normalize(EnergyGains.Basis.REFERENCE, 1, Map.of("test:factor", bad)));
        }
        assertThrows(IllegalArgumentException.class, () -> EnergyGains.normalize(EnergyGains.Basis.REFERENCE, 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> EnergyGains.normalize(EnergyGains.Basis.FIXED, 1, Map.of("test:factor", 2.0)));
        assertThrows(IllegalArgumentException.class, () -> EnergyGains.normalize(EnergyGains.Basis.BASE, -1, Map.of()));
    }
    @Test void currentRecipientStatAndCesApplyOnceAfterReferenceNormalization() throws Exception {
        var h = new Harness(); h.stat("grantor", 100, 0);
        var low = (EnergyActions.Result) h.grant(.04, EnergyGains.Basis.BASE, Map.of()).result();
        assertEquals(.032, low.grant().credited(), 1e-12, "grantor stat must not scale recipient gain");
        h.stat("recipient", 100, 0);
        var result = h.grant(.072, EnergyGains.Basis.REFERENCE, Map.of("test:stat", new Value.Constant(2.25, Unit.MULTIPLIER), "test:ces", new Value.Constant(.8, Unit.MULTIPLIER)));
        var gain = (EnergyActions.Result) result.result();
        assertEquals(.04, gain.normalized().base()); assertEquals(.072, gain.grant().requested()); assertEquals(.072, gain.grant().credited(), 1e-12);
        assertEquals(.04, gain.calculation().orElseThrow().inputs().base().value());
        assertTrue(gain.calculation().orElseThrow().contributionConfidence().contains(NumericContribution.Confidence.FITTED));
        assertEquals(List.of("chorus:resource_granted", "chorus:resource_changed"), result.emitted().stream().map(RuleEngine.Signal::type).toList());
        assertEquals(0, h.state().resources().get(new ResourceState.Key("recipient", ENERGY)).value(), "pure action evaluation preserves input state");
    }
    @Test void fixedGainsBypassBothProfilesAndRetainOverflowAccounting() throws Exception {
        var h = new Harness(); h.stat("recipient", 100, 0);
        var result = (EnergyActions.Result) h.grant(1.2, EnergyGains.Basis.FIXED, Map.of()).result();
        assertEquals(1.2, result.grant().scaled()); assertEquals(1, result.grant().credited()); assertEquals(.2, result.grant().overflow());
        assertTrue(result.calculation().isEmpty());
        var full = h.state().withResource(result.grant().after()); var e = h.evaluation();
        var repeated = (RuleEngine.Local<EffectState>) new EnergyActions.Grant(ENERGY, Evaluation.Target.VICTIM, new Value.Constant(.2, Unit.CHARGE), EnergyGains.Basis.FIXED, Map.of(), Set.of(), Map.of())
                .execute(new Evaluation(full, e.context(), e.buffs(), e.results(), e.resources(), e.retainedResults(), e.program()));
        assertEquals(List.of("chorus:resource_granted"), repeated.emitted().stream().map(RuleEngine.Signal::type).toList());
    }
    @Test void explicitTriggerFactorsDoNotInheritTriggerEventTagsAndErrorsDoNotWrite() throws Exception {
        var h = new Harness(); h.stat("recipient", 100, 0);
        h.session.start(0, SourceChange.bind(new EffectSource("cms", "chorus_d2:class_mod_energy", "recipient", ORIGIN, Set.of())));
        assertEquals(.072, ((EnergyActions.Result) h.grant(.04, EnergyGains.Basis.BASE, Map.of()).result()).grant().scaled(), 1e-12);
        var invalid = new EnergyActions.Grant(ENERGY, Evaluation.Target.VICTIM, new Value.Constant(.04, Unit.CHARGE), EnergyGains.Basis.BASE, Map.of(), Set.of("chorus_d2:class_mod_energy"), Map.of());
        assertThrows(IllegalArgumentException.class, () -> invalid.execute(h.evaluation()));
        var valid = new EnergyActions.Grant(ENERGY, Evaluation.Target.VICTIM, new Value.Constant(.04, Unit.CHARGE), EnergyGains.Basis.BASE, Map.of(), Set.of("chorus_d2:class_mod_energy"), Map.of("trigger_multiplier", new Value.Constant(.5, Unit.MULTIPLIER)));
        var gain = (EnergyActions.Result) ((RuleEngine.Local<EffectState>) valid.execute(h.evaluation())).result();
        assertEquals(.036, gain.grant().scaled(), 1e-12);
        assertEquals(0, h.state().resources().get(new ResourceState.Key("recipient", ENERGY)).value());
    }
    @Test void passiveRateUsesCurrentStatWithoutChangingIndependentRecipientScalar() throws Exception {
        var h = new Harness(); h.stat("recipient", 100, 1_000_000); h.stat("recipient", 0, 2_000_000);
        var account = h.state().resources().get(new ResourceState.Key("recipient", ENERGY));
        assertEquals((1 + 2.75) / 145.2, account.value(), 1e-12);
        assertEquals(1 / 145.2, h.program.resourceRate(h.state(), account).perSecond(), 1e-12);
        assertEquals(.032, ((EnergyActions.Result) h.grant(.04, EnergyGains.Basis.BASE, Map.of()).result()).grant().scaled(), 1e-12);
    }
    @Test void fittedCurvesPreserveSeventyAndHundredBranchesAndClampOnlyTheStatInput() throws Exception {
        var h = new Harness();
        for (double s : new double[] {-1, 0, 30, 69.999, 70, 99.999, 100, 200}) {
            h.stat("recipient", s, 0); double x = Math.clamp(s, 0, 100);
            double passive = x >= 100 ? 2.75 : x >= 70 ? 2.10898698 + .00639461 * x : 1 + .004273626 * x + .000300195 * x * x - .000000637618 * x * x * x;
            var account = h.state().resources().get(new ResourceState.Key("recipient", ENERGY));
            assertEquals(passive / 145.2, h.program.resourceRate(h.state(), account).perSecond(), 1e-12);
            assertEquals(.04 * .8 * (1 + .625 * (1 - StrictMath.cos(Math.PI * x / 100))), ((EnergyActions.Result) h.grant(.04, EnergyGains.Basis.BASE, Map.of()).result()).grant().scaled(), 1e-12);
        }
    }
    @Test void currentPublishedNumericCellsMatchWithoutUsingRoundedDisplayText() throws Exception {
        // Armor Stat Info GViz values saved in data/d2-research/2026-10-10/armor-energy.json.
        var h = new Harness();
        double[][] cells = {{10, 1.0721181419999999, .030589677315529085}, {70, 2.55660968, .9923657826827956}, {100, 2.75, 1.25}};
        for (var row : cells) {
            h.stat("recipient", row[0], 0);
            var account = h.state().resources().get(new ResourceState.Key("recipient", ENERGY));
            assertEquals(row[1], 145.2 * h.program.resourceRate(h.state(), account).perSecond(), 1e-12);
            assertEquals(.032 * (1 + row[2]), ((EnergyActions.Result) h.grant(.04, EnergyGains.Basis.BASE, Map.of()).result()).grant().scaled(), 1e-12);
        }
    }
    @Test void codecsRoundTripAndRejectAmbiguousBasisOrWrongProfileUnits() throws Exception {
        var data = json("spike_energy_inputs");
        var action = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(2).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().get("action");
        var parsed = EffectCodecs.ACTION.parse(JsonOps.INSTANCE, action).getOrThrow();
        assertEquals(parsed, EffectCodecs.ACTION.parse(JsonOps.INSTANCE, EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE, parsed).getOrThrow()).getOrThrow());
        action.getAsJsonObject().remove("reference_factors"); assertTrue(EffectCodecs.ACTION.parse(JsonOps.INSTANCE, action).error().isPresent());
        var r = json("threaded_spike").getAsJsonArray("resources").get(0);
        var definition = EffectCodecs.RESOURCE.parse(JsonOps.INSTANCE, r).getOrThrow();
        assertEquals(definition, EffectCodecs.RESOURCE.parse(JsonOps.INSTANCE, EffectCodecs.RESOURCE.encodeStart(JsonOps.INSTANCE, definition).getOrThrow()).getOrThrow());
        var bad = JsonParser.parseString("""
            {"version":"1","resources":[{"id":"test:r","capacity":1,"initial":0,"gain_profile":"test:p"}],
            "profiles":[{"id":"test:p","version":"1","input_unit":"damage","steps":[]}]}
            """).getAsJsonObject();
        assertTrue(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, bad).error().isPresent());
    }
    @Test void cosineUsesRadiansAndStrictFiniteDomains() {
        var c = new Curve.Cosine(0, Math.PI, Curve.Boundary.CLAMP);
        assertEquals(1, c.evaluate(-1)); assertEquals(-1, c.evaluate(4)); assertEquals(0, c.evaluate(Math.PI / 2), 1e-15);
        assertThrows(IllegalArgumentException.class, () -> c.evaluate(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Curve.Cosine(0, 1, Curve.Boundary.ERROR).evaluate(2));
        assertThrows(IllegalArgumentException.class, () -> new Curve.Cosine(1, 0, Curve.Boundary.ERROR));
        assertEquals(c, StatCodecs.CURVE.parse(JsonOps.INSTANCE, StatCodecs.CURVE.encodeStart(JsonOps.INSTANCE, c).getOrThrow()).getOrThrow());
    }
}
