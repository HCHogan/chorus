package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.rule.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class TargetIterationTest {
    private static final EffectSource SOURCE = source("test:area");
    private static JsonArray actions(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    private static TargetQuery.Result selected(TimelineEngine.Transition<EffectState> t, String... targets) {
        var query = (TargetQuery) t.actions().getFirst().command();
        return new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, Arrays.stream(targets).map(id -> new TargetQuery.Target(id, 0)).toList());
    }
    private static TimelineEngine.Transition<EffectState> start(TimelineEngine<EffectState> engine, EffectState state) {
        return send(engine, engine.initial(state.withSource(SOURCE)), 0, "test:area", event(SOURCE));
    }
    private static DamageReceipt hit(double effective) {
        return new DamageReceipt("test-hit", effective == 0 ? DamageReceipt.Outcome.FAILED : DamageReceipt.Outcome.APPLIED, 0, 0, effective, Optional.empty(), false);
    }
    private static HealingReceipt healed(TimelineEngine.Transition<EffectState> waiting) {
        var command = (HealingCommand) waiting.actions().getFirst().command();
        return new HealingReceipt(waiting.actions().getFirst().id().toString(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
    }
    @Test void eachTargetUsesItsOwnActualDamageReceiptAndOldReceiptsCannotAdvanceTheNextIteration() throws Exception {
        var engine = engine(load("target_iteration")); var query = start(engine, EffectState.empty()); var snapshot = selected(query, "a", "b", "c");
        var a = complete(engine, query, snapshot); assertEquals("a", ((DamageCommand) a.actions().getFirst().command()).target());
        var healA = complete(engine, a, hit(2)); assertEquals(1, ((HealingCommand) healA.actions().getFirst().command()).amount());
        var b = complete(engine, healA, healed(healA)); assertEquals("b", ((DamageCommand) b.actions().getFirst().command()).target());
        assertEquals(a.actions().getFirst().id().pc(), b.actions().getFirst().id().pc()); assertNotEquals(a.actions().getFirst().id(), b.actions().getFirst().id());
        var duplicate = engine.transition(b.state(), new RuleEngine.Completed(a.actions().getFirst().id(), hit(2)));
        assertEquals(b.state(), duplicate.state()); assertTrue(duplicate.actions().isEmpty());
        var c = complete(engine, b, hit(0)); assertEquals("c", ((DamageCommand) c.actions().getFirst().command()).target());
        var healC = complete(engine, c, hit(.5)); assertEquals(.25, ((HealingCommand) healC.actions().getFirst().command()).amount());
        var done = complete(engine, healC, healed(healC)); assertEquals("test:done", ((Action.CueCommand) done.actions().getFirst().command()).cue());
        assertEquals(Set.of("nearby"), done.state().engine().frames().getFirst().bindings().keySet());
        assertEquals(List.of(new TargetQuery.Target("a", 0), new TargetQuery.Target("b", 0), new TargetQuery.Target("c", 0)), snapshot.targets());
        assertTrue(complete(engine, done, RuleEngine.Empty.INSTANCE).state().idle());
    }
    @Test void emptyAndUnavailableQueriesHaveDistinctFlagsAndBothSkipTheLoop() throws Exception {
        var engine = engine(load("target_iteration"));
        for (var outcome : TargetQuery.Outcome.values()) {
            var waiting = start(engine, EffectState.empty()); var query = (TargetQuery) waiting.actions().getFirst().command();
            var result = new TargetQuery.Result(query, outcome, List.of()); var done = complete(engine, waiting, result);
            assertInstanceOf(Action.CueCommand.class, done.actions().getFirst().command());
            assertEquals(0, ResultShape.TARGETS.read("count", result).value());
            assertEquals(outcome == TargetQuery.Outcome.AVAILABLE, ResultShape.TARGETS.flag("available", result));
        }
    }
    @Test void nestedQueriesCanUseOuterTargetsAndInnerBodiesCanReadBothTargetBindings() throws Exception {
        var data = json("target_iteration"); var steps = actions(data); var query = steps.get(0).deepCopy();
        var inner = query.getAsJsonObject(); inner.addProperty("as", "neighbors");
        inner.getAsJsonObject("action").add("center", JsonParser.parseString("{\"binding\":\"outer\"}"));
        inner.getAsJsonObject("action").add("exclude", JsonParser.parseString("[\"source_owner\",{\"binding\":\"outer\"}]"));
        var outer = JsonParser.parseString("""
                {"for_each":"nearby","as":"outer","do":[]}
                """).getAsJsonObject(); var body = outer.getAsJsonArray("do"); body.add(inner);
        body.add(JsonParser.parseString("""
                {"for_each":"neighbors","as":"inner","do":[
                  {"if":{"type":"chorus:target_is","left":{"binding":"inner"},"right":{"binding":"outer"}},
                   "then":[{"type":"chorus:play_cue","cue":"test:same","target":{"binding":"inner"}}],
                   "else":[{"type":"chorus:play_cue","cue":"test:other","target":{"binding":"outer"}}]}
                ]}
                """)); steps.set(1, outer);
        var compiled = compile(data); var encoded = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, compiled.program()).getOrThrow();
        assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var engine = engine(compiled); var first = start(engine, EffectState.empty()); var nested = complete(engine, first, selected(first, "a", "b"));
        assertEquals(new TargetQuery.EntityCenter("a"), ((TargetQuery) nested.actions().getFirst().command()).center());
        assertEquals(Set.of("player", "a"), ((TargetQuery) nested.actions().getFirst().command()).exclude());
        // include_center is false, so only the unequal branch is reached with this receipt.
        var cue = complete(engine, nested, selected(nested, "c")); var command = (Action.CueCommand) cue.actions().getFirst().command();
        assertEquals("test:other", command.cue()); assertEquals("a", command.target());
        var second = complete(engine, cue, RuleEngine.Empty.INSTANCE); assertEquals(new TargetQuery.EntityCenter("b"), ((TargetQuery) second.actions().getFirst().command()).center());
        assertEquals(Set.of("player", "b"), ((TargetQuery) second.actions().getFirst().command()).exclude());
        var done = complete(engine, second, selected(second)); assertInstanceOf(Action.CueCommand.class, done.actions().getFirst().command());
    }
    @Test void invalidScopeReferencesWrongShapesAndBadUnitsAreRejected() throws Exception {
        for (String invalid : List.of(
                "{\"type\":\"chorus:play_cue\",\"cue\":\"test:x\",\"target\":{\"binding\":\"target\"}}",
                "{\"for_each\":\"target\",\"as\":\"x\",\"do\":[]}",
                "{\"for_each\":\"nearby\",\"as\":\"nearby\",\"do\":[]}",
                "{\"type\":\"chorus:play_cue\",\"cue\":\"test:x\",\"target\":{\"binding\":\"nearby\"}}",
                "{\"type\":\"chorus:heal\",\"amount\":{\"type\":\"chorus:result\",\"binding\":\"hit\",\"field\":\"effective\"}}")) {
            var data = json("target_iteration"); actions(data).add(JsonParser.parseString(invalid));
            assertThrows(RuntimeException.class, () -> compile(data), invalid);
        }
        for (String unit : List.of("chorus:damage", "chorus:count")) {
            var data = json("target_iteration"); actions(data).get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("radius").addProperty("unit", unit);
            assertThrows(RuntimeException.class, () -> compile(data));
        }
        var data = json("target_iteration"); actions(data).get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("radius").addProperty("value", -1);
        assertThrows(RuntimeException.class, () -> compile(data));
    }
    @Test void malformedQueryReceiptsAreRejectedWithoutExecutingAnyTargets() throws Exception {
        var query = new TargetQuery("player", 5, TargetQuery.Relation.ANY, "player", false);
        assertThrows(IllegalArgumentException.class, () -> new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, List.of(new TargetQuery.Target("b", 0), new TargetQuery.Target("a", 0))));
        assertThrows(IllegalArgumentException.class, () -> new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, List.of(new TargetQuery.Target("a", 0), new TargetQuery.Target("a", 0))));
        assertThrows(IllegalArgumentException.class, () -> new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, List.of(new TargetQuery.Target("player", 0))));
        var engine = engine(load("target_iteration")); var waiting = start(engine, EffectState.empty());
        var wrong = new TargetQuery("player", 6, TargetQuery.Relation.ANY, "player", false);
        var failed = engine.transition(waiting.state(), new RuleEngine.Completed(waiting.actions().getFirst().id(), new TargetQuery.Result(wrong, TargetQuery.Outcome.AVAILABLE, List.of())));
        assertTrue(failed.state().engine().failure().isPresent()); assertTrue(failed.actions().isEmpty());
    }
    private static JsonObject refundProgram(boolean paymentInside) throws Exception {
        var data = json("target_iteration"); data.add("resources", JsonParser.parseString("[{\"id\":\"test:energy\",\"capacity\":3,\"initial\":2}]"));
        var steps = actions(data); var query = steps.get(0).deepCopy(); steps.asList().clear(); steps.add(query);
        var payment = JsonParser.parseString("""
                {"action":{"type":"chorus:spend_resource","resource":"test:energy","payment":"cast",
                 "amount":{"type":"chorus:constant","value":1,"unit":"chorus:charge_fraction"}},"as":"cost"}
                """);
        var loop = JsonParser.parseString("""
                {"for_each":"nearby","as":"target","do":[
                  {"type":"chorus:refund_cost","cost":"cost","fraction":{"type":"chorus:constant","value":0.7,"unit":"chorus:multiplier"}},
                  {"type":"chorus:play_cue","cue":"test:barrier","target":{"binding":"target"}}
                ]}
                """).getAsJsonObject();
        if (paymentInside) loop.getAsJsonArray("do").asList().addFirst(payment); else steps.add(payment);
        steps.add(loop); steps.add(JsonParser.parseString("{\"type\":\"chorus:play_cue\",\"cue\":\"test:done\"}")); return data;
    }
    @Test void outerPaymentRefundBudgetSurvivesLoopScopeCleanupAndWorldWaits() throws Exception {
        var engine = engine(compile(refundProgram(false))); var key = new ResourceState.Key("player", "test:energy");
        var query = start(engine, EffectState.empty().withResource(new ResourceState(key, 2, 3, 0)));
        var a = complete(engine, query, selected(query, "a", "b", "c")); assertEquals(1.7, a.state().engine().domain().resources().get(key).value());
        var b = complete(engine, a, RuleEngine.Empty.INSTANCE); assertEquals(2, b.state().engine().domain().resources().get(key).value());
        var c = complete(engine, b, RuleEngine.Empty.INSTANCE); assertEquals(2, c.state().engine().domain().resources().get(key).value());
        var done = complete(engine, c, RuleEngine.Empty.INSTANCE); assertEquals(1, done.state().engine().frames().getFirst().retainedResults().size());
        assertEquals(2, done.state().engine().domain().resources().get(key).value());
    }
    @Test void repeatedPaymentInstructionCreatesDistinctPaidReceiptsPerIteration() throws Exception {
        var engine = engine(compile(refundProgram(true))); var key = new ResourceState.Key("player", "test:energy");
        var query = start(engine, EffectState.empty().withResource(new ResourceState(key, 2, 3, 0)));
        var a = complete(engine, query, selected(query, "a", "b")); assertEquals(1.7, a.state().engine().domain().resources().get(key).value());
        var b = complete(engine, a, RuleEngine.Empty.INSTANCE); assertEquals(1.4, b.state().engine().domain().resources().get(key).value());
        var done = complete(engine, b, RuleEngine.Empty.INSTANCE); var retained = done.state().engine().frames().getFirst().retainedResults();
        assertEquals(2, retained.size()); assertEquals(2, retained.values().stream().map(r -> ((Resources.CostResult) r).receipt().operation()).distinct().count());
        for (var r : retained.values()) assertEquals(.7, ((Resources.CostResult) r).receipt().refundClaimed());
        assertEquals(Set.of("nearby"), done.state().engine().frames().getFirst().bindings().keySet());
    }
}
