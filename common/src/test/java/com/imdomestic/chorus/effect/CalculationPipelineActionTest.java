package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalculationPipelineActionTest {
    static CalculationActions.CalculatePipeline query(Set<String> tags) {
        return new CalculationActions.CalculatePipeline(List.of(CalculationActionTest.PROFILE, CalculationActionTest.PROFILE), Evaluation.Target.SOURCE_OWNER,
                new Value.Constant(10, Unit.SECOND), ActionOrigin.EVENT, tags, Map.of("continuity_extension", new Value.Constant(5, Unit.SECOND)), Optional.of(Evaluation.Target.SELF));
    }
    @Test void eachOccurrenceQueriesItsModifiersWithTheSameExplicitRecipientAndContext() throws Exception {
        var p = load("continuity"); var s = EffectState.empty().withSource(CalculationActionTest.fragment("one", "owner"));
        var e = CalculationActionTest.evaluation(p, s); var action = query(Set.of("chorus_d2:sever"));
        var out = assertInstanceOf(RuleEngine.Local.class, action.execute(e)); var r = (CalculationActions.PipelineResult) out.result();
        assertSame(s, out.state()); assertTrue(out.emitted().isEmpty()); assertEquals("owner", r.holder()); assertEquals("owner", r.query().victim());
        assertEquals(CalculationActionTest.TRIGGER, r.query().source()); assertEquals("trigger", r.query().actor());
        assertEquals(new Measure(3, Unit.COUNT), r.query().numbers().get("kept")); assertEquals(new Measure(5, Unit.SECOND), r.query().numbers().get("continuity_extension"));
        assertEquals(20, r.calculation().output().value()); assertEquals(2, r.calculation().steps().size());
        for (var step : r.calculation().steps()) assertEquals(1, step.trace().contributions().size());
        var noTags = (CalculationActions.PipelineResult) ((RuleEngine.Local<?>) query(Set.of()).execute(e)).result(); assertEquals(10, noTags.calculation().output().value());
        assertEquals(30, r.calculation().withBase(new Measure(20, Unit.SECOND)).output().value());
    }
    @Test void validationChecksAllLinksAndBothResultUnitsAndRoundTrips() throws Exception {
        var p = load("continuity"); var v = new Validation(Map.of(), Map.of(), false, Map.of(CalculationActionTest.PROFILE, p.program().profiles().getFirst()));
        var action = query(Set.of("chorus_d2:sever")); var shape = action.validate(v); assertEquals(Unit.SECOND, shape.unit("input")); assertEquals(Unit.SECOND, shape.unit("value"));
        assertEquals(action, EffectCodecs.ACTION.parse(JsonOps.INSTANCE, EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE, action).getOrThrow()).getOrThrow());
        var missing = new CalculationActions.CalculatePipeline(List.of(CalculationActionTest.PROFILE, "test:missing"), action.target(), action.input(), action.origin(), action.tags(), action.numbers(), action.victim());
        assertThrows(IllegalArgumentException.class, () -> missing.validate(v));
        assertThrows(IllegalArgumentException.class, () -> new CalculationActions.CalculatePipeline(List.of(), action.target(), action.input(), action.origin(), action.tags(), action.numbers(), action.victim()));
    }
    @Test void capturedTypedPipelineResultSurvivesDelayedWorldUse() {
        var data = JsonParser.parseString("""
          {"version":"pipeline-test","profiles":[
            {"id":"test:seconds","version":"pipeline-test","input_unit":"stat_point","steps":[{"type":"chorus:curve","id":"curve","curve":{"type":"chorus:polynomial","coefficients":[0,0.1],"minimum":0,"maximum":100,"boundary":"error"},"output_unit":"second"}]},
            {"id":"test:healing","version":"pipeline-test","input_unit":"second","steps":[{"type":"chorus:curve","id":"curve","curve":{"type":"chorus:polynomial","coefficients":[0,2],"minimum":0,"maximum":100,"boundary":"error"},"output_unit":"damage"}]}],
           "bundles":[{"id":"test:driver","rules":[{"id":"query","on":"test:query","do":[
             {"action":{"type":"chorus:calculate_pipeline","profiles":["test:seconds","test:healing"],"input":{"type":"chorus:constant","value":30,"unit":"stat_point"}},"as":"result"},
             {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"do":[{"type":"chorus:heal","amount":{"type":"chorus:result","binding":"result","field":"value"}}]}
           ]}]}]}
          """).getAsJsonObject();
        var p = compile(data); var healed = new ArrayList<Double>(); var driver = source("test:driver");
        var session = new EffectSession(engine(p), EffectState.empty().withSource(driver), request -> {
            var heal = (com.imdomestic.chorus.effect.combat.HealingCommand) request.command(); healed.add(heal.amount());
            return new com.imdomestic.chorus.effect.combat.HealingReceipt("heal", heal, com.imdomestic.chorus.effect.combat.HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
        });
        session.start(0, new RuleEngine.Signal("test:query", event(driver))); assertTrue(healed.isEmpty()); session.observe(100_000, List.of()); assertEquals(List.of(6.0), healed);
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow().program());
        data.getAsJsonArray("profiles").get(1).getAsJsonObject().addProperty("input_unit", "damage");
        assertThrows(RuntimeException.class, () -> compile(data));
    }
}
