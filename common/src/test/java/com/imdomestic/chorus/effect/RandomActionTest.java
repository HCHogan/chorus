package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.random.RandomState;
import com.imdomestic.chorus.rule.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RandomActionTest {
    static final EffectSource SOURCE = source("test:random");
    static EffectState initial(long seed) { return EffectState.empty().withRandom(new RandomState(seed, 0)).withSource(SOURCE); }
    static final class Harness {
        final EffectSession session; final List<Double> heals = new ArrayList<>(); final List<Long> cursors = new ArrayList<>(); boolean fail;
        Harness(long seed, int budget) throws Exception {
            var p = load("random"); session = new EffectSession(p.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), budget), initial(seed), request -> {
                var heal = (HealingCommand) request.command(); heals.add(heal.amount()); cursors.add(state().random().cursor());
                if (fail) throw new IllegalStateException("unknown heal after sampling");
                return new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        void roll() { session.start(state().buffs().timeMicros(), new RuleEngine.Signal("test:roll", event(SOURCE))); }
        void until(long time) { session.observe(time, List.of()); }
    }
    @Test void differentPumpBudgetsAndReplayProduceIdenticalSamplesAndDelayedValues() throws Exception {
        var slow = new Harness(123, 1); var fast = new Harness(123, 10000);
        for (var h : List.of(slow, fast)) { h.roll(); h.roll(); h.session.start(0, SourceChange.remove(SOURCE.instance())); h.until(100_000); }
        assertEquals(slow.heals, fast.heals); assertEquals(slow.state(), fast.state()); assertEquals(2, slow.state().random().cursor());
        assertEquals(6, slow.heals.size()); assertEquals(slow.heals.get(0), slow.heals.get(1)); assertEquals(slow.heals.get(0), slow.heals.get(4));
        assertEquals(slow.heals.get(2), slow.heals.get(3)); assertEquals(slow.heals.get(2), slow.heals.get(5)); assertNotEquals(slow.heals.get(0), slow.heals.get(2));
        assertEquals(List.of(1L, 1L, 2L, 2L, 2L, 2L), slow.cursors);
    }
    @Test void falseRuleAndResultQueriesCannotConsumeAdditionalRandomNumbers() throws Exception {
        var p = load("random"); var engine = engine(p);
        var skipped = send(engine, engine.initial(initial(42)), 0, "test:skip", event(SOURCE)); assertEquals(new RandomState(42, 0), skipped.state().engine().domain().random());
        var waiting = send(engine, skipped.state(), 0, "test:roll", event(SOURCE));
        var fact = waiting.state().engine().facts().stream().filter(e -> e.signal().type().equals(RandomActions.EVENT)).map(e -> (RandomActions.Fact) e.signal().payload()).findFirst().orElseThrow();
        assertEquals("2a", fact.event().references().get("seed")); assertEquals("0", fact.event().references().get("cursor_before")); assertEquals("1", fact.event().references().get("cursor_after"));
        assertEquals(fact.sample().after(), waiting.state().engine().domain().random()); assertEquals(fact.sample().value(), fact.event().numbers().get("value"));
        var state = waiting.state(); assertThrows(IllegalStateException.class, () -> engine.transition(state, RuleEngine.Pump.INSTANCE));
        assertEquals(fact.sample().after(), state.engine().domain().random());
    }
    @Test void unknownWorldOutcomeRetainsAdvancedStreamAndBoundReceiptWithoutRedrawing() throws Exception {
        var h = new Harness(0, 1); h.fail = true; assertThrows(IllegalStateException.class, h::roll);
        assertEquals(1, h.state().random().cursor()); assertTrue(h.session.state().engine().pending().isPresent());
        var binding = h.session.state().engine().frames().getFirst().bindings().get("roll"); assertInstanceOf(RandomState.Sample.class, binding);
        var before = h.state(); assertThrows(IllegalStateException.class, () -> h.until(100_000)); assertEquals(before, h.state()); assertEquals(1, h.heals.size());
    }
    @Test void duplicateWorldCompletionCannotRerollOrChangeDelayedContinuation() throws Exception {
        var p = load("random"); var engine = engine(p); var waiting = send(engine, engine.initial(initial(99)), 0, "test:roll", event(SOURCE));
        var command = (HealingCommand) waiting.actions().getFirst().command(); var id = waiting.actions().getFirst().id();
        var receipt = new HealingReceipt("first", command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
        var after = complete(engine, waiting, receipt); var duplicate = engine.transition(after.state(), new RuleEngine.Completed(id, receipt));
        assertEquals(after.state(), duplicate.state()); assertEquals(1, duplicate.state().engine().domain().random().cursor());
    }
    @Test void codecRejectsHiddenRandomValuesWrongUnitsInvalidIntervalsAndUnknownDistributions() throws Exception {
        var p = load("random").program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        for (String fault : List.of("units", "bounds", "integer", "unknown", "hidden")) {
            var data = json("random"); var steps = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
            var action = steps.get(0).getAsJsonObject().getAsJsonObject("action");
            switch (fault) {
                case "units" -> action.getAsJsonObject("upper").addProperty("unit", "round");
                case "bounds" -> action.getAsJsonObject("upper").addProperty("value", 1);
                case "integer" -> { action.addProperty("distribution", "uniform_integer"); action.getAsJsonObject("lower").addProperty("value", 1.5); }
                case "unknown" -> action.addProperty("distribution", "assume_destiny_distribution");
                case "hidden" -> steps.get(1).getAsJsonObject().add("amount", action.deepCopy());
            }
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
    }
}
