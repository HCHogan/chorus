package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ObservedFactsTest {
    private record Amount(int value) implements ActionResult {}
    private record Work() implements WorldCommand {}
    private record MeasuredWork(int value) implements WorldCommand {}
    private record Committed(int value) implements Payload {}
    private static Signal signal(String type) { return new Signal(type, Empty.INSTANCE); }
    private static <S> Transition<S> settle(RuleEngine<S> engine, Transition<S> result) {
        for (int i = 0; result.needsPump() && i < 200; i++) result = engine.transition(result.state(), Pump.INSTANCE);
        assertFalse(result.needsPump()); assertTrue(result.state().failure().isEmpty()); return result;
    }
    private static EventRule<List<Event>> record(String type, List<Signal> emitted) {
        return new EventRule<>("record/" + type, type, (_, _) -> true, List.of(new Instruction<>((state, context) -> {
            var next = new ArrayList<>(state); next.add(context.event());
            return new Local<>(List.copyOf(next), Empty.INSTANCE, emitted);
        }, "")));
    }

    @Test void externalFactsShareOneBoundaryAndReactionsFollowTheAlreadyCommittedBatch() {
        var engine = new RuleEngine<List<Event>>("test", List.of(record("child", List.of(signal("reaction"))),
                record("parent", List.of()), record("reaction", List.of())), 1);
        var started = engine.transition(engine.initial(List.of()), new Start(123, List.of(signal("child"), signal("parent"))));
        assertThrows(IllegalStateException.class, () -> engine.transition(started.state(), new Start(124, signal("parent"))));
        var result = settle(engine, started);
        var facts = result.state().domain();
        assertEquals(List.of("child", "parent", "reaction"), facts.stream().map(e -> e.signal().type()).toList());
        assertTrue(facts.stream().allMatch(e -> e.timeMicros() == 123 && e.root() == 1));
        assertEquals(Optional.of(1L), facts.get(0).parent()); assertEquals(Optional.of(1L), facts.get(1).parent());
        assertEquals(Optional.of(facts.getFirst().id()), facts.getLast().parent());
    }

    @Test void receiptUnwrapsTypedResultAndDefersNativeFactsUntilCurrentRulesFinish() {
        Action<List<Event>> world = new Action<>() {
            @Override public Outcome<List<Event>> step(List<Event> state, Context context) { return new Await<>(new Work()); }
            @Override public Local<List<Event>> complete(List<Event> state, Context context, ActionResult result) {
                assertEquals(new Amount(4), result);
                return new Local<>(state, result, List.of(signal("managed")));
            }
        };
        var root = new EventRule<List<Event>>("root", "root", (_, _) -> true, List.of(new Instruction<>(world, "damage"),
                new Instruction<>((state, context) -> {
                    assertEquals(4, context.result("damage", Amount.class).value());
                    assertTrue(state.isEmpty(), "Native reaction ran before the current action list completed");
                    return new Local<>(List.of(context.event()), Empty.INSTANCE, List.of());
                }, "")));
        var engine = new RuleEngine<List<Event>>("test", List.of(root, record("root", List.of()),
                record("native", List.of()), record("managed", List.of())), 1);
        var awaiting = settle(engine, engine.transition(engine.initial(List.of()), new Start(90, signal("root"))));
        var operation = awaiting.actions().getFirst().id();
        var receipt = new WorldReceipt(new Amount(4), List.of(signal("native")));
        var result = settle(engine, engine.transition(awaiting.state(), new Completed(operation, receipt)));
        assertEquals(List.of("root", "root", "native", "managed"), result.state().domain().stream().map(e -> e.signal().type()).toList());
        for (var fact : result.state().domain().subList(2, 4)) {
            assertEquals(1, fact.root()); assertEquals(Optional.of(1L), fact.parent()); assertEquals(90, fact.timeMicros());
        }
        assertEquals(receipt, result.state().receipts().get(operation));
        assertEquals(result.state(), engine.transition(result.state(), new Completed(operation, receipt)).state());
        var altered = new WorldReceipt(new Amount(4), List.of(signal("different")));
        assertThrows(IllegalArgumentException.class, () -> engine.transition(result.state(), new Completed(operation, altered)));
    }

    @Test void failedCompletionRetainsNativeEvidenceWithoutReplayingIt() {
        Action<Integer> broken = new Action<>() {
            @Override public Outcome<Integer> step(Integer state, Context context) { return new Await<>(new Work()); }
            @Override public Local<Integer> complete(Integer state, Context context, ActionResult result) { throw new IllegalStateException("bad rule"); }
        };
        var rule = new EventRule<Integer>("broken", "root", (_, _) -> true, List.of(new Instruction<>(broken, "")));
        var engine = new RuleEngine<Integer>("test", List.of(rule), 20);
        var pending = engine.transition(engine.initial(7), new Start(0, signal("root")));
        var op = pending.actions().getFirst().id(); var receipt = new WorldReceipt(new Amount(4), List.of(signal("native")));
        var failed = engine.transition(pending.state(), new Completed(op, receipt));
        assertEquals(7, failed.state().domain()); assertTrue(failed.state().failure().isPresent());
        assertEquals(receipt, failed.state().receipts().get(op));
        assertEquals(failed.state(), engine.transition(failed.state(), new Completed(op, receipt)).state());
    }

    @Test void completionUsesTheIssuedCommandWhileTheNextActionSeesReconciledState() {
        Action<Integer> measured = new Action<>() {
            @Override public Outcome<Integer> step(Integer state, Context context) {
                assertTrue(context.pendingCommand().isEmpty(), "A previous action's command must not leak");
                return new Await<>(new MeasuredWork(state));
            }
            @Override public Local<Integer> complete(Integer state, Context context, ActionResult result) {
                assertEquals(((Amount) result).value(), context.command(MeasuredWork.class).value());
                assertNotEquals(state.intValue(), context.command(MeasuredWork.class).value(), "World commit has already changed state");
                return new Local<>(state, result, List.of());
            }
        };
        var rule = new EventRule<Integer>("measured", "root", (_, _) -> true,
                List.of(new Instruction<>(measured, "first"), new Instruction<>(measured, "second")));
        var engine = new RuleEngine<Integer>("test", List.of(rule), 1,
                (_, _) -> List.of(new RuleBinding("measured", "measured", Empty.INSTANCE)),
                (state, writes) -> writes instanceof Committed c ? c.value() : noWrites(state, writes));
        var first = settle(engine, engine.transition(engine.initial(8), new Start(0, signal("root"))));
        assertEquals(Optional.of(new MeasuredWork(8)), first.state().frames().getFirst().pendingCommand());
        var firstOp = first.actions().getFirst().id(); var receipt = new WorldReceipt(new Amount(8), List.of(), new Committed(3));
        var next = settle(engine, engine.transition(first.state(), new Completed(firstOp, receipt)));
        assertEquals(new MeasuredWork(3), next.actions().getFirst().command());
        assertEquals(next.state(), engine.transition(next.state(), new Completed(firstOp, receipt)).state());
        var done = settle(engine, engine.transition(next.state(), new Completed(next.actions().getFirst().id(), new WorldReceipt(new Amount(3), List.of(), new Committed(1)))));
        assertTrue(done.state().idle()); assertEquals(1, done.state().domain());
    }

    private record Timed(long time, List<String> observations) {}
    private static TimelineEngine<Timed> timeline() {
        var rules = List.of("expiry", "first", "second").stream().map(type -> new EventRule<Timed>(type, type, (_, _) -> true,
                List.of(new Instruction<Timed>((state, context) -> {
                    var facts = new ArrayList<>(state.observations()); facts.add(type + "@" + context.event().timeMicros());
                    return new Local<>(new Timed(state.time(), List.copyOf(facts)), Empty.INSTANCE, List.of());
                }, "")))).toList();
        return new TimelineEngine<>("test", rules, (_, event) -> rules.stream().filter(rule -> rule.eventType().equals(event.signal().type()))
                .map(rule -> new RuleBinding(rule.definition(), rule.definition(), Empty.INSTANCE)).toList(), new TimelineEngine.Clock<>() {
                    @Override public long time(Timed state) { return state.time(); }
                    @Override public long nextDeadline(Timed state) { return state.time() < 50 ? 50 : TimelineEngine.NEVER; }
                    @Override public Local<Timed> advance(Timed state, long until) {
                        return new Local<>(new Timed(until, state.observations()), Empty.INSTANCE, until == 50 ? List.of(signal("expiry")) : List.of());
                    }
                }, 1);
    }
    @Test void timelineSettlesExpiryBeforeDeliveringAllObservedFacts() {
        var engine = timeline();
        var result = engine.transition(engine.initial(new Timed(0, List.of())), new Start(90, List.of(signal("first"), signal("second"))));
        for (int i = 0; result.needsPump() && i < 200; i++) result = engine.transition(result.state(), Pump.INSTANCE);
        assertTrue(result.state().idle()); assertTrue(result.state().engine().failure().isEmpty());
        assertEquals(List.of("expiry@50", "first@90", "second@90"), result.state().engine().domain().observations());
    }
    @Test void observedBatchCannotInjectTheReservedClockEvent() {
        var engine = timeline();
        assertThrows(IllegalArgumentException.class, () -> engine.transition(engine.initial(new Timed(0, List.of())),
                new Start(90, List.of(signal("first"), signal("chorus:internal/advance_time")))));
    }
}
