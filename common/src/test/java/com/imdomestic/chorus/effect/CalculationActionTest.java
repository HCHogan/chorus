package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalculationActionTest {
    static final String PROFILE = "chorus_d2:strand_debuff_duration";
    static final BuffInstance.Origin OWNER = new BuffInstance.Origin("owner", "producer", "gun", "", Set.of("test:bound"));
    static final BuffInstance.Origin TRIGGER = new BuffInstance.Origin("trigger", "other", "other-gun", "", Set.of("test:trigger"));
    static EffectSource fragment(String instance, String holder) { return new EffectSource(instance, "chorus_d2:continuity", holder, new BuffInstance.Origin(holder, instance, "", ""), Set.of()); }
    static Evaluation evaluation(CompiledEffects p, EffectState s, RuleEngine.Payload scope, EffectEvent event) {
        var context = new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), s.buffs().timeMicros(), new RuleEngine.Signal("test:query", event)), "query", scope, Map.of());
        return new Evaluation(s, context, Map.of(), Map.of(), Map.of(), Map.of(), Optional.of(p));
    }
    static Evaluation evaluation(CompiledEffects p, EffectState s) {
        return evaluation(p, s, new EffectSource("driver", "test:unused", "owner", OWNER, Set.of()),
                new EffectEvent("trigger", "victim", TRIGGER, Set.of("test:trigger_tag"), Map.of("continuity_extension", new Measure(99, Unit.SECOND), "kept", new Measure(3, Unit.COUNT))));
    }
    static CalculationActions.Calculate query(double base, double extension, String tag) {
        return new CalculationActions.Calculate(PROFILE, Evaluation.Target.SOURCE_OWNER, new Value.Constant(base, Unit.SECOND), ActionOrigin.BOUND,
                Set.of(tag), Map.of("continuity_extension", new Value.Constant(extension, Unit.SECOND)));
    }
    static CalculationActions.Result calculate(CalculationActions.Calculate action, Evaluation e) {
        var outcome = assertInstanceOf(RuleEngine.Local.class, action.execute(e));
        assertSame(e.state(), outcome.state()); assertTrue(outcome.emitted().isEmpty());
        return (CalculationActions.Result) outcome.result();
    }

    @Test void queryIsReadOnlyAndRetainsProfileTraceWhileSeparatingHolderOriginAndTriggerContext() throws Exception {
        var p = load("continuity"); var s = EffectState.empty().withSource(fragment("one", "owner")).withSource(fragment("two", "owner")).withSource(fragment("foreign", "victim"));
        var e = evaluation(p, s); var action = query(10, 5, "chorus_d2:sever"); var result = calculate(action, e);
        assertEquals("owner", result.holder()); assertEquals(OWNER, result.query().source());
        assertEquals("trigger", result.query().actor()); assertEquals("victim", result.query().victim());
        assertEquals(Set.of("chorus_d2:sever"), result.query().tags()); assertEquals(5, result.query().numbers().get("continuity_extension").value());
        assertEquals(3, result.query().numbers().get("kept").value()); assertEquals(99, e.event().numbers().get("continuity_extension").value());
        assertEquals(15, result.calculation().output().value()); assertEquals(PROFILE, result.calculation().trace().profile());
        assertEquals("compendium-2026-10-05", result.calculation().trace().version());
        assertEquals(2, result.calculation().trace().contributions().size());
        assertEquals(1, result.calculation().trace().contributions().stream().filter(CalculationTrace.ContributionTrace::selected).count());
        var eventOrigin = new CalculationActions.Calculate(action.profile(), action.target(), action.input(), ActionOrigin.EVENT, action.tags(), action.numbers());
        var other = calculate(eventOrigin, e); assertEquals(TRIGGER, other.query().source()); assertEquals("owner", other.holder());
    }

    @Test void sourceSpecificContinuityExtensionsHandleSuspendWithoutGuessingUniversalFiftyPercent() throws Exception {
        var p = load("continuity"); var e = evaluation(p, EffectState.empty().withSource(fragment("fragment", "owner")));
        for (var values : List.of(new double[]{10, 5, 15}, new double[]{5, 2.5, 7.5}))
            assertEquals(values[2], calculate(query(values[0], values[1], "chorus_d2:sever"), e).calculation().output().value());
        for (var values : List.of(new double[]{6, 2, 8}, new double[]{3, 1, 4}, new double[]{2, 1, 3}))
            assertEquals(values[2], calculate(query(values[0], values[1], "chorus_d2:suspend"), e).calculation().output().value());
        assertEquals(10, calculate(query(10, 5, "chorus_d2:woven_mail"), e).calculation().output().value());
        assertEquals(10, calculate(query(10, 5, "chorus_d2:sever"), evaluation(p, EffectState.empty().withSource(fragment("victim-fragment", "victim")))).calculation().output().value());
    }

    @Test void explicitQueryVictimQualifiesTargetDependentModifiersWithoutRebindingTheirHolder() throws Exception {
        var data = json("continuity");
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().add("if",
                JsonParser.parseString("{\"type\":\"chorus:target_is\",\"left\":\"self\",\"right\":\"victim\"}"));
        var p = compile(data); var e = evaluation(p, EffectState.empty().withSource(fragment("fragment", "owner")));
        var original = query(10, 5, "chorus_d2:sever"); assertEquals(10, calculate(original, e).calculation().output().value());
        var directed = new CalculationActions.Calculate(PROFILE, original.target(), original.input(), ActionOrigin.EVENT,
                original.tags(), original.numbers(), Optional.of(Evaluation.Target.SELF));
        var result = calculate(directed, e); assertEquals(15, result.calculation().output().value());
        assertEquals("owner", result.holder()); assertEquals("owner", result.query().victim()); assertEquals(TRIGGER, result.query().source());
        assertEquals("victim", e.event().victim(), "query does not mutate the triggering event");
    }

    @Test void buffOwnedQueryCanSelectItsOriginalApplierInsteadOfItsAffectedHolder() throws Exception {
        var p = link("continuity", "strand_defense"); var s = EffectState.empty().withSource(fragment("owner-fragment", "owner"));
        s = s.withBuffs(Buffs.grant(s.buffs(), p.buff("chorus_d2:sever"), "carrier", "carrier", OWNER, 1, 1, 10_000_000).store());
        var buff = s.buffs().instances().values().iterator().next(); var e = evaluation(p, s, new BuffRules.Scope(buff, false), event(fragment("trigger", "trigger")));
        assertEquals(15, calculate(query(10, 5, "chorus_d2:sever"), e).calculation().output().value());
        var self = new CalculationActions.Calculate(PROFILE, Evaluation.Target.SELF, new Value.Constant(10, Unit.SECOND), ActionOrigin.BOUND,
                Set.of("chorus_d2:sever"), Map.of("continuity_extension", new Value.Constant(5, Unit.SECOND)));
        assertEquals(10, calculate(self, e).calculation().output().value());
    }

    @Test void missingOrWrongExtensionMeasurementsFailOnlyWhenTheEquippedModifierActuallyReadsThem() throws Exception {
        var p = load("continuity"); var with = evaluation(p, EffectState.empty().withSource(fragment("fragment", "owner")));
        var clean = new EffectEvent("trigger", "victim", TRIGGER, Set.of("chorus_d2:sever"), Map.of());
        with = evaluation(p, with.state(), with.context().scope(), clean);
        var missing = new CalculationActions.Calculate(PROFILE, Evaluation.Target.SOURCE_OWNER, new Value.Constant(10, Unit.SECOND), ActionOrigin.BOUND, Set.of("chorus_d2:sever"), Map.of());
        var ready = with; assertThrows(IllegalArgumentException.class, () -> calculate(missing, ready));
        var wrong = new CalculationActions.Calculate(PROFILE, Evaluation.Target.SOURCE_OWNER, missing.input(), ActionOrigin.BOUND, missing.tags(), Map.of("continuity_extension", new Value.Constant(5, Unit.COUNT)));
        assertThrows(IllegalArgumentException.class, () -> calculate(wrong, ready));
        assertEquals(10, calculate(missing, evaluation(p, EffectState.empty(), ready.context().scope(), clean)).calculation().output().value());
        var noTags = new CalculationActions.Calculate(PROFILE, Evaluation.Target.SOURCE_OWNER, missing.input(), ActionOrigin.BOUND, Set.of(), Map.of());
        assertEquals(10, calculate(noTags, ready).calculation().output().value(), "trigger tags do not leak into a query");
    }

    @Test void capturedCalculationKeepsItsValueWhileAQueryInsideALaterCallbackUsesCurrentLoadout() throws Exception {
        var p = link("continuity", "continuity_inputs", "strand_defense");
        var input = new EffectSource("driver", "test:continuity_inputs", "owner", OWNER, Set.of());
        var checks = new ArrayList<StatusResult.Check>();
        var session = new EffectSession(engine(p), EffectState.empty().withSource(input).withSource(fragment("fragment", "owner")), request -> {
            var check = (StatusResult.Check) request.command(); checks.add(check); return new StatusResult.Checked(check, StatusResult.Decision.ALLOWED);
        });
        session.start(0, new RuleEngine.Signal("test:later", new EffectEvent("owner", "victim", OWNER, Set.of(), Map.of())));
        session.start(0, SourceChange.remove("fragment")); session.start(0, SourceChange.remove("driver"));
        session.observe(200_000, List.of());
        assertEquals(List.of(15_000_000L, 10_000_000L), checks.stream().map(StatusResult.Check::duration).toList());
        assertEquals(10_200_000, buff(session.state(), "chorus_d2:sever", "victim").stacks().getFirst().expiresAt());
        assertTrue(session.state().idle() && session.state().engine().failure().isEmpty());
    }

    @Test void calculationResultsExposeDifferentInputAndOutputUnitsAndRoundTripThroughTheDsl() {
        var data = JsonParser.parseString("""
          {"version":"query-test","profiles":[{"id":"test:convert","version":"query-test","input_unit":"second","steps":[
            {"type":"chorus:curve","id":"conversion","curve":{"type":"chorus:polynomial","coefficients":[0,2],"minimum":0,"maximum":100,"boundary":"clamp"},"output_unit":"damage"}]}],
          "bundles":[{"id":"test:driver","rules":[{"id":"convert","on":"test:convert","do":[
            {"action":{"type":"chorus:calculate","profile":"test:convert","input":{"type":"chorus:constant","value":3,"unit":"second"}},"as":"calculated"},
            {"type":"chorus:heal","amount":{"type":"chorus:result","binding":"calculated","field":"value"}}
          ]}]}]}
          """).getAsJsonObject();
        var p = compile(data); var heals = new ArrayList<HealingCommand>(); var input = source("test:driver");
        var session = new EffectSession(engine(p), EffectState.empty().withSource(input), request -> {
            var heal = (HealingCommand) request.command(); heals.add(heal); return new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
        });
        session.start(0, new RuleEngine.Signal("test:convert", event(input))); assertEquals(6, heals.getFirst().amount());
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow(); assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonObject("amount").addProperty("field", "input");
        assertThrows(RuntimeException.class, () -> compile(data), "seconds cannot be consumed as healing damage units");
    }

    @Test void querySchemasRejectUnknownProfilesWrongUnitsFutureBindingsAndUnknownFields() throws Exception {
        var p = link("continuity", "continuity_inputs", "strand_defense");
        for (String mutation : List.of("profile", "unit", "binding", "target", "victim", "blank", "extra")) {
            var data = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow().getAsJsonObject();
            var bundle = data.getAsJsonArray("bundles").asList().stream().map(JsonElement::getAsJsonObject).filter(b -> b.get("id").getAsString().equals("test:continuity_inputs")).findFirst().orElseThrow();
            var action = bundle.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action");
            switch (mutation) {
                case "profile" -> action.addProperty("profile", "test:missing");
                case "unit" -> action.add("input", JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":10,\"unit\":\"damage\"}"));
                case "binding" -> action.getAsJsonObject("numbers").add("continuity_extension", JsonParser.parseString("{\"type\":\"chorus:result\",\"binding\":\"duration\",\"field\":\"value\"}"));
                case "target" -> action.add("target", JsonParser.parseString("{\"binding\":\"missing\"}"));
                case "victim" -> action.add("victim", JsonParser.parseString("{\"binding\":\"missing\"}"));
                case "blank" -> action.getAsJsonObject("numbers").add(" ", JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"second\"}"));
                case "extra" -> action.addProperty("silently_guess", true);
            }
            assertThrows(RuntimeException.class, () -> EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow(), mutation);
        }
    }
}
