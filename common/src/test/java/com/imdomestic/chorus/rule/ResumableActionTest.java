package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.resource.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResumableActionTest {
    private record Phase(int number, OperationId identity) implements WorldCommand {}
    private record Value(int number) implements ActionResult {}
    private record Write(int value) implements Payload {}
    private static final Signal INPUT = signal("test:input");
    private static Signal signal(String type) { return new Signal(type, Empty.INSTANCE); }
    private static <S> EventRule<S> rule(String id, String event, List<Instruction<S>> actions) {
        return new EventRule<>(id, event, (_, _) -> true, actions);
    }
    private static <S> Transition<S> pump(RuleEngine<S> engine, Transition<S> next) {
        while (next.needsPump()) next = engine.transition(next.state(), Pump.INSTANCE);
        assertTrue(next.state().failure().isEmpty(), next.state().failure().toString());
        return next;
    }
    private static Phase phase(Context context) {
        var phase = context.command(Phase.class);
        assertEquals(context.operation(), phase.identity());
        return phase;
    }
    private static <S> Await<S> next(Context context, int phase) {
        return new Await<>(new Phase(phase, context.operation().nextInvocation()));
    }

    @Test void threeWorldPhasesBindOnceWithoutRepeatingStepOrGuardAndIgnoreDuplicateReceipts() {
        var steps = new AtomicInteger(); var guards = new AtomicInteger(); var resumes = new AtomicInteger();
        var action = new Action<Integer>() {
            public Outcome<Integer> step(Integer state, Context context) {
                steps.incrementAndGet(); assertEquals(new Value(7), context.bindings().get("seed"));
                return new Await<>(new Phase(0, context.operation()));
            }
            public Outcome<Integer> resume(Integer state, Context context, ActionResult actual) {
                int n = phase(context).number(); resumes.incrementAndGet();
                assertEquals(new Value(n + 1), actual); assertFalse(context.bindings().containsKey("final"));
                return n < 2 ? next(context, n + 1) : new Local<>(state + 1, actual, List.of());
            }
            public Local<Integer> complete(Integer state, Context context, ActionResult actual) { throw new AssertionError("resume owns the phases"); }
        };
        var engine = new RuleEngine<>("test", List.of(rule("root", INPUT.type(), List.of(
                new Instruction<Integer>((s, _) -> new Local<>(s, new Value(7), List.of()), "seed"),
                new Instruction<>(action, "final", (_, _) -> { guards.incrementAndGet(); return true; }, 1),
                new Instruction<>((s, c) -> {
                    assertEquals(new Value(3), c.bindings().get("final"));
                    return new Local<>(s + 10, Empty.INSTANCE, List.of());
                }, "")))), 1);
        var initial = engine.initial(0); var pending = pump(engine, engine.transition(initial, new Start(123, INPUT)));
        long frame = pending.actions().getFirst().id().frame();
        for (int n = 0; n < 3; n++) {
            var request = pending.actions().getFirst(); var receipt = new Value(n + 1);
            assertEquals(new OperationId(frame, 1, n), request.id());
            assertEquals(new Phase(n, request.id()), request.command());
            var before = pending.state(); var done = new Completed(request.id(), receipt);
            pending = pump(engine, engine.transition(before, done));
            assertEquals(Optional.of(request.id()), before.pending()); // Earlier immutable boundary remains intact.
            var duplicate = engine.transition(pending.state(), done);
            assertSame(pending.state(), duplicate.state()); assertTrue(duplicate.actions().isEmpty());
            var current = pending.state();
            assertThrows(IllegalArgumentException.class, () -> engine.transition(current, new Completed(request.id(), new Value(99))));
        }
        assertTrue(pending.state().idle()); assertEquals(11, pending.state().domain()); assertEquals(123, pending.state().timeMicros());
        assertEquals(3, pending.state().receipts().size()); assertEquals(1, steps.get()); assertEquals(1, guards.get()); assertEquals(3, resumes.get());
        assertEquals(0, initial.domain()); assertTrue(initial.receipts().isEmpty());
    }

    @Test void nativeWritesPrecedeEachResumeAndObservedFactsKeepTheirOrderAndCausality() {
        var seen = new ArrayList<Event>(); var order = new ArrayList<String>();
        var action = new Action<Integer>() {
            public Outcome<Integer> step(Integer s, Context c) { return new Await<>(new Phase(0, c.operation())); }
            public Outcome<Integer> resume(Integer s, Context c, ActionResult r) {
                int n = phase(c).number(); assertEquals((n + 1) * 10, s);
                order.add("resume" + n);
                return n == 0 ? next(c, 1) : new Local<>(s + 1, r, List.of(signal("test:local")));
            }
        };
        var definitions = new ArrayList<EventRule<Integer>>();
        definitions.add(rule("root", INPUT.type(), List.of(new Instruction<>(action, "value"))));
        definitions.add(rule("sibling", INPUT.type(), List.of(new Instruction<>((s, _) -> {
            order.add("sibling"); return new Local<>(s, Empty.INSTANCE, List.of());
        }, ""))));
        for (String name : List.of("first", "second", "local", "deferred"))
            definitions.add(rule(name, "test:" + name, List.of(new Instruction<>((s, c) -> {
                seen.add(c.event()); order.add(name); return new Local<>(s, Empty.INSTANCE, List.of());
            }, ""))));
        var engine = new RuleEngine<>("test", definitions, 50, (_, event) -> definitions.stream()
                .filter(r -> r.eventType().equals(event.signal().type())).map(r -> new RuleBinding(r.definition(), r.definition(), Empty.INSTANCE)).toList(),
                (s, writes) -> writes == Empty.INSTANCE ? s : ((Write) writes).value());
        var first = engine.transition(engine.initial(0), new Start(50, INPUT)); var origin = first.state().frames().getFirst().event();
        var second = engine.transition(first.state(), new Completed(first.actions().getFirst().id(),
                new WorldReceipt(new Value(1), List.of(signal("test:first"), afterReactions(signal("test:deferred"))), new Write(10))));
        assertEquals(List.of("resume0"), order); assertEquals(2, second.state().facts().size());
        assertEquals(10, second.state().domain()); assertEquals(2, second.state().frames().size());
        var done = engine.transition(second.state(), new Completed(second.actions().getFirst().id(),
                new WorldReceipt(new Value(2), List.of(signal("test:second")), new Write(20))));
        assertTrue(done.state().idle()); assertEquals(21, done.state().domain());
        assertEquals(List.of("resume0", "resume1", "sibling", "first", "second", "local", "deferred"), order);
        for (var event : seen) {
            assertEquals(origin.root(), event.root()); assertEquals(Optional.of(origin.id()), event.parent()); assertEquals(50, event.timeMicros());
        }
        assertEquals(4, seen.stream().map(Event::id).distinct().count());
    }

    @Test void actualCostClaimsSurviveSeveralPhasesAndAnOlderFinalBinding() {
        var account = new ResourceState(new ResourceState.Key("player", "test:energy"), 1, 1, 0);
        var paid = Resources.spend(account, "payment", .4);
        var refunded = Resources.refund(paid.after(), paid.receipt(), .5);
        var sealed = new RetainedCosts.Handle("saved", new Resources.CostReceipt("payment", account.key(), .4, .4), 0, 10);
        for (Resources.CostResult stronger : List.of(refunded, sealed)) {
            var action = new Action<Integer>() {
                public Outcome<Integer> step(Integer s, Context c) { return new Await<>(new Phase(0, c.operation())); }
                public Outcome<Integer> resume(Integer s, Context c, ActionResult actual) {
                    int n = phase(c).number();
                    assertEquals(n == 0 ? paid.receipt() : stronger.receipt(), Resources.latestClaim(paid.receipt(), c.retainedResults().values()));
                    return n < 2 ? next(c, n + 1) : new Local<>(s, paid, List.of());
                }
            };
            var engine = new RuleEngine<>("test", List.of(rule("root", INPUT.type(), List.of(new Instruction<>(action, "stale"),
                    new Instruction<>((s, c) -> {
                        assertEquals(paid, c.bindings().get("stale"));
                        assertEquals(stronger.receipt(), Resources.latestClaim(paid.receipt(), c.retainedResults().values()));
                        return new Local<>(1, Empty.INSTANCE, List.of());
                    }, "")))), 50);
            var pending = engine.transition(engine.initial(0), new Start(0, INPUT));
            for (ActionResult actual : List.of(paid, new WorldReceipt(stronger, List.of()), Empty.INSTANCE))
                pending = engine.transition(pending.state(), new Completed(pending.actions().getFirst().id(), actual));
            assertTrue(pending.state().failure().isEmpty()); assertTrue(pending.state().idle()); assertEquals(1, pending.state().domain());
        }
    }

    @Test void conflictingIntermediatePaymentStopsBeforeResumeAndPreservesBothWorldReceipts() {
        var account = new ResourceState(new ResourceState.Key("player", "test:energy"), 1, 1, 0);
        var paid = Resources.spend(account, "payment", .4); var conflict = Resources.spend(account, "payment", .2);
        var resumes = new AtomicInteger();
        var action = new Action<Integer>() {
            public Outcome<Integer> step(Integer s, Context c) { return new Await<>(new Phase(0, c.operation())); }
            public Outcome<Integer> resume(Integer s, Context c, ActionResult actual) { resumes.incrementAndGet(); return next(c, 1); }
        };
        var engine = new RuleEngine<>("test", List.of(rule("root", INPUT.type(), List.of(new Instruction<>(action, "")))), 50);
        var pending = engine.transition(engine.initial(7), new Start(0, INPUT)); var first = pending.actions().getFirst().id();
        pending = engine.transition(pending.state(), new Completed(first, paid)); var second = pending.actions().getFirst().id();
        var failed = engine.transition(pending.state(), new Completed(second, conflict));
        assertEquals(1, resumes.get()); assertEquals(7, failed.state().domain()); assertTrue(failed.actions().isEmpty());
        assertTrue(failed.state().failure().isPresent()); assertEquals(Map.of(first, paid, second, conflict), failed.state().receipts());
        assertTrue(engine.transition(failed.state(), new Completed(second, conflict)).actions().isEmpty());
    }

    @Test void secondResumeFailurePreservesReconciledDomainAndDoesNotReplayEarlierPhases() {
        var action = new Action<Integer>() {
            public Outcome<Integer> step(Integer s, Context c) { return new Await<>(new Phase(0, c.operation())); }
            public Outcome<Integer> resume(Integer s, Context c, ActionResult actual) {
                if (phase(c).number() == 0) return next(c, 1);
                assertEquals(20, s); throw new IllegalArgumentException("bad result after the second actual write");
            }
        };
        var engine = new RuleEngine<>("test", List.of(rule("root", INPUT.type(), List.of(new Instruction<>(action, "")))), 50,
                (_, _) -> List.of(new RuleBinding("root", "root", Empty.INSTANCE)),
                (s, writes) -> writes == Empty.INSTANCE ? s : ((Write) writes).value());
        var pending = engine.transition(engine.initial(0), new Start(0, INPUT));
        pending = engine.transition(pending.state(), new Completed(pending.actions().getFirst().id(), new WorldReceipt(new Value(1), List.of(), new Write(10))));
        var actual = new WorldReceipt(new Value(2), List.of(), new Write(20)); var op = pending.actions().getFirst().id();
        var failed = engine.transition(pending.state(), new Completed(op, actual));
        assertEquals(20, failed.state().domain()); assertEquals(actual, failed.state().receipts().get(op));
        assertTrue(failed.state().failure().isPresent()); assertTrue(failed.actions().isEmpty()); assertTrue(failed.state().pending().isEmpty());
        assertSame(failed.state(), engine.transition(failed.state(), new Completed(op, actual)).state());
        assertTrue(engine.transition(failed.state(), Pump.INSTANCE).actions().isEmpty());
    }

    @Test void loopIterationsKeepDistinctPhaseIdentitiesAndTheirLexicalItem() {
        var operations = new ArrayList<OperationId>(); var items = new ArrayList<Integer>();
        var action = new Action<Integer>() {
            public Outcome<Integer> step(Integer s, Context c) { return new Await<>(new Phase(0, c.operation())); }
            public Outcome<Integer> resume(Integer s, Context c, ActionResult r) {
                items.add(c.result("item", Value.class).number());
                return phase(c).number() == 0 ? next(c, 1) : new Local<>(s + c.result("item", Value.class).number(), r, List.of());
            }
        };
        var engine = new RuleEngine<>("test", List.of(rule("root", INPUT.type(), List.of(
                new Instruction<Integer>(new ForEach<>((_, _) -> List.of(new Value(2), new Value(3)), 1), "item"),
                new Instruction<>(action, "result"), new Instruction<>(new EndEach<>(1), ""),
                new Instruction<>((s, c) -> { assertTrue(c.bindings().isEmpty()); return new Local<>(s, Empty.INSTANCE, List.of()); }, "")))), 1);
        var pending = pump(engine, engine.transition(engine.initial(0), new Start(0, INPUT)));
        while (!pending.state().idle()) {
            var op = pending.actions().getFirst().id(); operations.add(op);
            pending = pump(engine, engine.transition(pending.state(), new Completed(op, Empty.INSTANCE)));
        }
        assertEquals(5, pending.state().domain()); assertEquals(List.of(2, 2, 3, 3), items);
        assertEquals(List.of(0, 1, 2, 3), operations.stream().map(OperationId::invocation).toList());
        assertEquals(1, operations.stream().map(OperationId::frame).distinct().count());
    }

    @Test void invocationExhaustionStopsWithoutWrappingOrLosingTheActualReceipt() {
        var action = new Action<Integer>() {
            public Outcome<Integer> step(Integer s, Context c) { return new Await<>(new Phase(0, c.operation())); }
            public Outcome<Integer> resume(Integer s, Context c, ActionResult r) { return new Await<>(new Phase(1, c.operation())); }
        };
        var engine = new RuleEngine<>("test", List.of(rule("root", INPUT.type(), List.of(new Instruction<>(action, "")))), 50);
        var initial = engine.transition(engine.initial(0), new Start(0, INPUT)).state(); var frame = initial.frames().getFirst();
        var op = new OperationId(frame.id(), frame.pc(), Integer.MAX_VALUE);
        var exhausted = new Frame(frame.id(), frame.event(), frame.rule(), frame.definition(), frame.scope(), frame.pc(), frame.started(),
                frame.bindings(), frame.iterations(), Map.of(frame.pc(), Integer.MAX_VALUE), frame.retainedResults(), Optional.of(new Phase(0, op)));
        var pending = new State<>(initial.version(), initial.domain(), initial.timeMicros(), initial.nextEvent(), initial.nextFrame(),
                initial.facts(), List.of(exhausted), Optional.of(op), initial.receipts(), initial.failure());
        var failed = engine.transition(pending, new Completed(op, new Value(7)));
        assertTrue(failed.actions().isEmpty()); assertTrue(failed.state().failure().orElseThrow().message().contains("ArithmeticException"));
        assertEquals(new Value(7), failed.state().receipts().get(op)); assertTrue(failed.state().pending().isEmpty());
        assertThrows(ArithmeticException.class, op::nextInvocation);
    }

    @Test void arbitrarilyManyPhasesYieldAtWorldBoundariesWithoutAChainLimit() {
        var action = new Action<Integer>() {
            public Outcome<Integer> step(Integer s, Context c) { return new Await<>(new Phase(0, c.operation())); }
            public Outcome<Integer> resume(Integer s, Context c, ActionResult r) { return next(c, phase(c).number() + 1); }
        };
        var engine = new RuleEngine<>("test", List.of(rule("root", INPUT.type(), List.of(new Instruction<>(action, "")))), 1);
        var pending = pump(engine, engine.transition(engine.initial(0), new Start(0, INPUT)));
        for (int n = 0; n < 80; n++) {
            var op = pending.actions().getFirst().id();
            assertEquals(n, op.invocation()); assertEquals(1, pending.state().frames().size());
            pending = engine.transition(pending.state(), new Completed(op, Empty.INSTANCE));
            assertTrue(pending.state().failure().isEmpty()); assertFalse(pending.needsPump());
        }
        assertEquals(80, pending.state().pending().orElseThrow().invocation()); assertEquals(0, pending.state().timeMicros());
        var current = pending.state();
        assertThrows(IllegalStateException.class, () -> engine.transition(current, new Start(1, INPUT)));
        assertThrows(IllegalStateException.class, () -> engine.transition(current, Pump.INSTANCE));
    }

    private record Timed(long time, boolean expired) {}
    @Test void timelineDoesNotAdvanceOrDispatchTheRequestedInputBetweenWorldPhases() { timeline(false); }
    @Test void aFailedExpiryResumeAbandonsTheLaterInputInsteadOfAdvancingItsClock() { timeline(true); }
    private static void timeline(boolean fail) {
        var delivered = new ArrayList<Long>();
        var action = new Action<Timed>() {
            public Outcome<Timed> step(Timed s, Context c) { return new Await<>(new Phase(0, c.operation())); }
            public Outcome<Timed> resume(Timed s, Context c, ActionResult r) {
                assertEquals(5, s.time()); assertEquals(5, c.event().timeMicros());
                if (phase(c).number() == 0) return next(c, 1);
                if (fail) throw new IllegalArgumentException("expiry completion failed");
                return new Local<>(s, r, List.of());
            }
        };
        var definitions = List.of(rule("expiry", "test:expiry", List.of(new Instruction<>(action, ""))),
                rule("input", INPUT.type(), List.of(new Instruction<Timed>((s, c) -> {
                    delivered.add(s.time()); return new Local<>(s, Empty.INSTANCE, List.of());
                }, ""))));
        var clock = new TimelineEngine.Clock<Timed>() {
            public long time(Timed s) { return s.time(); }
            public long nextDeadline(Timed s) { return s.expired() ? TimelineEngine.NEVER : 5; }
            public Local<Timed> advance(Timed s, long until) {
                return new Local<>(new Timed(until, true), Empty.INSTANCE, s.expired() ? List.of() : List.of(signal("test:expiry")));
            }
        };
        var engine = new TimelineEngine<>("test", definitions, (_, event) -> definitions.stream().filter(r -> r.eventType().equals(event.signal().type()))
                .map(r -> new RuleBinding(r.definition(), r.definition(), Empty.INSTANCE)).toList(), clock, 1);
        var input = new Start(10, INPUT); var pending = engine.transition(engine.initial(new Timed(0, false)), input);
        while (pending.needsPump()) pending = engine.transition(pending.state(), Pump.INSTANCE);
        var first = new Completed(pending.actions().getFirst().id(), new Value(1));
        pending = engine.transition(pending.state(), first);
        assertEquals(5, pending.state().engine().domain().time()); assertEquals(Optional.of(input), pending.state().requested()); assertTrue(delivered.isEmpty());
        assertSame(pending.state(), engine.transition(pending.state(), first).state());
        var suspended = pending.state(); assertThrows(IllegalStateException.class, () -> engine.checkpoint(suspended));
        pending = engine.transition(pending.state(), new Completed(pending.actions().getFirst().id(), new Value(2)));
        while (pending.needsPump()) pending = engine.transition(pending.state(), Pump.INSTANCE);
        if (fail) {
            assertTrue(delivered.isEmpty()); assertEquals(5, pending.state().engine().domain().time());
            assertEquals(Optional.of(input), pending.state().abandoned()); assertTrue(pending.state().engine().failure().isPresent());
        } else {
            assertEquals(List.of(10L), delivered); assertTrue(pending.state().idle()); assertTrue(pending.state().engine().failure().isEmpty());
            assertEquals(10, engine.checkpoint(pending.state()).timeMicros());
        }
        assertEquals(2, pending.state().receipts().size());
    }
}
