package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.BuffsTest.*;
import static com.imdomestic.chorus.effect.buff.BuffDefinition.*;
import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.rule.TimelineEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EffectTimelineTest {
    private record Observation(long time, double energy, String kind) implements WorldCommand {}
    private record Ack(int value) implements ActionResult {}
    private static Signal signal(String type) { return new Signal(type, Empty.INSTANCE); }
    private static EventRule<EffectState> rule(String id, String event, Action<EffectState> action) {
        return new EventRule<>(id, event, (_, _) -> true, List.of(new Instruction<>(action, "")));
    }
    private static RuleResolver<EffectState> staticSources(List<EventRule<EffectState>> rules) {
        return (_, event) -> rules.stream().filter(rule -> rule.eventType().equals(event.signal().type()))
                .map(rule -> new RuleBinding(rule.definition(), rule.definition(), Empty.INSTANCE)).toList();
    }
    private static TimelineEngine.Transition<EffectState> pump(TimelineEngine<EffectState> engine, TimelineEngine.Transition<EffectState> result) {
        for (int i = 0; i < 2000 && result.needsPump(); i++) result = engine.transition(result.state(), Pump.INSTANCE);
        assertFalse(result.needsPump());
        return result;
    }
    private static List<Observation> run(TimelineEngine<EffectState> engine, EffectState initial, long until) {
        var result = pump(engine, engine.transition(engine.initial(initial), new Start(until, signal("request"))));
        var observations = new ArrayList<Observation>();
        for (int i = 0; i < 100 && !result.state().idle(); i++) {
            assertTrue(result.state().engine().failure().isEmpty(), result.state().engine().failure().toString());
            var request = result.actions().getFirst(); observations.add((Observation) request.command());
            result = pump(engine, engine.transition(result.state(), new Completed(request.id(), new Ack(1))));
        }
        assertTrue(result.state().idle());
        assertTrue(result.state().engine().failure().isEmpty(), result.state().engine().failure().toString());
        return observations;
    }

    @Test void expiryReactionChangesTheNextIntegrationSegmentBeforeExternalRequest() {
        var first = definition("test:first-rate", 1, 500_000, TimerMode.SHARED, Decay.ALL, Refresh.NONE, OnStow.KEEP, false, false);
        var second = definition("test:second-rate", 1, 250_000, TimerMode.SHARED, Decay.ALL, Refresh.NONE, OnStow.KEEP, false, false);
        var resourceKey = new ResourceState.Key("player", "test:energy");
        var store = grant(BuffStore.empty(), first, A, 1).store();
        var initial = new EffectState(store, Map.of(resourceKey, new ResourceState(resourceKey, 0, 2, 0)), Map.of());
        var onEnd = new EventRule<EffectState>("next-rate", "chorus:buff_ended",
                (_, context) -> ((BuffRules.Scope) context.scope()).owns((Buffs.Change) context.event().signal().payload()),
                List.of(new Instruction<>((state, _) -> {
                    assertEquals(500_000, state.buffs().timeMicros());
                    assertEquals(.2, state.resources().get(resourceKey).value(), 1e-12);
                    var applied = grant(state.buffs(), second, A, 1);
                    return new Local<>(state.withBuffs(applied.store()), applied.receipt(), applied.signals());
                }, "")));
        var sources = new BuffRules<>(Map.of(first, List.of(onEnd)), EffectState::buffs);
        var external = rule("external", "request", (state, context) -> {
            assertTrue(state.buffs().instances().isEmpty());
            return new Await<>(new Observation(context.event().timeMicros(), state.resources().get(resourceKey).value(), "external"));
        });
        var definitions = new ArrayList<>(sources.definitions()); definitions.add(external);
        RuleResolver<EffectState> resolver = (state, event) -> {
            var bindings = new ArrayList<>(sources.resolve(state, event));
            bindings.addAll(staticSources(List.of(external)).resolve(state, event)); return bindings;
        };
        var clock = new EffectClock((state, _) -> new EffectClock.Rate(state.buffs().active(key(first, A)).isPresent() ? .4
                : state.buffs().active(key(second, A)).isPresent() ? .6 : .1, List.of()));
        var engine = new TimelineEngine<>("1", definitions, resolver, clock, 1);
        var observations = run(engine, initial, 2 * SECOND);
        assertEquals(1, observations.size());
        assertEquals(.4 * .5 + .6 * .25 + .1 * 1.25, observations.getFirst().energy(), 1e-12);
        assertEquals(2 * SECOND, observations.getFirst().time());
        assertEquals(0, initial.resources().get(resourceKey).value());
        assertEquals(1, initial.buffs().instances().size());
    }

    @Test void periodicFactsHaveNoTickDriftAndReceiptsSurviveIntermediateBoundaries() {
        var pulse = rule("pulse", "pulse", (_, context) -> new Await<>(new Observation(context.event().timeMicros(), 0, "pulse")));
        var external = rule("external", "request", (_, context) -> new Await<>(new Observation(context.event().timeMicros(), 0, "external")));
        var rules = List.of(pulse, external);
        var engine = new TimelineEngine<>("1", rules, staticSources(rules), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1);
        var initial = EffectState.empty().schedule(new EffectState.Timer("dot", 70_001, 70_001, -1, signal("pulse"), Optional.empty()));
        var result = pump(engine, engine.transition(engine.initial(initial), new Start(SECOND, signal("request"))));
        var firstOperation = result.actions().getFirst().id();
        var observations = new ArrayList<Observation>();
        for (int i = 0; i < 30 && !result.state().idle(); i++) {
            assertTrue(result.state().engine().failure().isEmpty());
            var request = result.actions().getFirst(); observations.add((Observation) request.command());
            if (i == 1) {
                var before = result;
                var duplicate = engine.transition(before.state(), new Completed(firstOperation, new Ack(1)));
                assertEquals(before.state(), duplicate.state()); assertTrue(duplicate.actions().isEmpty());
                assertThrows(IllegalArgumentException.class, () -> engine.transition(before.state(), new Completed(firstOperation, new Ack(2))));
                assertThrows(IllegalStateException.class, () -> engine.transition(before.state(), new Start(SECOND, signal("request"))));
                assertThrows(IllegalStateException.class, () -> engine.transition(before.state(), Pump.INSTANCE));
            }
            result = pump(engine, engine.transition(result.state(), new Completed(request.id(), new Ack(1))));
        }
        assertTrue(result.state().idle());
        assertEquals(15, observations.size());
        for (int i = 0; i < 14; i++) assertEquals((i + 1) * 70_001L, observations.get(i).time());
        assertEquals(new Observation(SECOND, 0, "external"), observations.getLast());
        assertEquals(15 * 70_001L, result.state().engine().domain().timers().get("dot").dueAt());
        assertEquals(15, result.state().receipts().size());
        var duplicate = engine.transition(result.state(), new Completed(firstOperation, new Ack(1)));
        assertEquals(result.state(), duplicate.state());
    }

    @Test void timerBoundToBuffDoesNotFireAtItsExpiryOrAttachToANewGeneration() {
        var definition = definition("test:duration", 1, 210_003, TimerMode.SHARED, Decay.ALL, Refresh.NONE, OnStow.KEEP, false, false);
        var buffs = grant(BuffStore.empty(), definition, A, 1).store();
        var instance = instance(buffs, definition, A);
        var initial = new EffectState(buffs, Map.of(), Map.of()).schedule(new EffectState.Timer("dot", 70_001, 70_001, 3, signal("pulse"),
                Optional.of(new EffectState.Lifetime(instance.key(), instance.generation()))));
        var rules = List.of(rule("pulse", "pulse", (_, context) -> new Await<>(new Observation(context.event().timeMicros(), 0, "pulse"))));
        var engine = new TimelineEngine<>("1", rules, staticSources(rules), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1);
        assertEquals(List.of(new Observation(70_001, 0, "pulse"), new Observation(140_002, 0, "pulse")), run(engine, initial, SECOND));
        var removed = Buffs.remove(buffs, instance.key(), Buffs.Reason.REMOVED).store();
        var replacement = initial.withBuffs(grant(removed, definition, A, 1).store());
        assertNotEquals(instance.generation(), instance(replacement.buffs(), definition, A).generation());
        assertTrue(run(engine, replacement, SECOND).isEmpty());
    }

    @Test void declaredResourceThresholdsGetTheirOwnReactionBoundaries() {
        var key = new ResourceState.Key("player", "test:two-charges");
        var initial = EffectState.empty().withResource(new ResourceState(key, 0, 2, 0));
        var rules = List.of(rule("energy", "chorus:resource_changed", (state, context) -> {
            var change = (EffectClock.ResourceChanged) context.event().signal().payload();
            assertEquals(context.event().timeMicros(), change.after().timeMicros());
            return new Await<>(new Observation(context.event().timeMicros(), change.after().value(), "energy"));
        }));
        var clock = new EffectClock((_, _) -> new EffectClock.Rate(.5, List.of(1.0)));
        var engine = new TimelineEngine<>("1", rules, staticSources(rules), clock, 2);
        assertEquals(List.of(new Observation(2 * SECOND, 1, "energy"), new Observation(4 * SECOND, 2, "energy")), run(engine, initial, 5 * SECOND));
        assertThrows(IllegalArgumentException.class, () -> clock.advance(initial, 3 * SECOND));
    }

    @Test void failedExpiryWorldCompletionKeepsReceiptAndAbandonsWaitingExternalRequest() {
        Action<EffectState> failure = new Action<>() {
            @Override public Outcome<EffectState> step(EffectState state, Context context) { return new Await<>(new Observation(context.event().timeMicros(), 0, "pulse")); }
            @Override public Local<EffectState> complete(EffectState state, Context context, ActionResult receipt) { throw new IllegalArgumentException("bad completion"); }
        };
        var rules = List.of(rule("failure", "pulse", failure), rule("external", "request", (_, _) -> { throw new AssertionError("External request must not run"); }));
        var engine = new TimelineEngine<>("1", rules, staticSources(rules), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 2);
        var initial = EffectState.empty().schedule(new EffectState.Timer("one", 100, 0, 1, signal("pulse"), Optional.empty()));
        var pending = pump(engine, engine.transition(engine.initial(initial), new Start(200, signal("request"))));
        var operation = pending.actions().getFirst().id();
        var failed = engine.transition(pending.state(), new Completed(operation, new Ack(1)));
        assertTrue(failed.state().engine().failure().isPresent());
        assertTrue(failed.state().requested().isEmpty());
        assertEquals(Optional.of(new Start(200, signal("request"))), failed.state().abandoned());
        assertEquals(100, failed.state().engine().domain().buffs().timeMicros());
        assertEquals(new Ack(1), failed.state().receipts().get(operation));
        assertEquals(failed.state(), engine.transition(failed.state(), new Completed(operation, new Ack(1))).state());
        assertThrows(IllegalStateException.class, () -> engine.transition(failed.state(), new Start(300, signal("request"))));
    }
}
