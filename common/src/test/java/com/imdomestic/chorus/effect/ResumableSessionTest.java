package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.TimelineEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResumableSessionTest {
    private record Phase(int number) implements WorldCommand {}
    private record Value(int number) implements ActionResult {}
    private static final ResourceState.Key KEY = new ResourceState.Key("player", "test:energy");
    private static final Signal INPUT = new Signal("test:input", Empty.INSTANCE);
    private static final Signal FACT = new Signal("test:fact", Empty.INSTANCE);
    private enum Failure { NONE, UNKNOWN, DIFFERENT, BEFORE_WRITE }

    private static final class Fixture {
        final List<WorldRequest> calls = new ArrayList<>();
        final List<EffectSession.WorldOperation> capabilities = new ArrayList<>();
        final List<Event> reactions = new ArrayList<>();
        final TimelineEngine<EffectState> engine;
        final EffectSession session;
        Fixture(Failure failure) {
            var action = new Action<EffectState>() {
                public Outcome<EffectState> step(EffectState s, Context c) { return new Await<>(new Phase(0)); }
                public Outcome<EffectState> resume(EffectState s, Context c, ActionResult actual) {
                    int phase = c.command(Phase.class).number();
                    if (phase == 0) {
                        assertInstanceOf(Resources.SpendResult.class, actual);
                        assertEquals(actual, c.retainedResults().get("payment"));
                        return new Await<>(new Phase(1));
                    }
                    assertEquals(new Value(1), actual); assertTrue(c.retainedResults().containsKey("payment"));
                    return new Local<>(s, new Value(2), List.of(FACT));
                }
            };
            var definitions = List.of(new EventRule<>("root", INPUT.type(), (_, _) -> true, List.of(new Instruction<>(action, "final"))),
                    new EventRule<EffectState>("observer", FACT.type(), (_, _) -> true, List.of(new Instruction<>((s, c) -> {
                        assertEquals(.3, s.resources().get(KEY).value(), 1e-12); reactions.add(c.event());
                        return new Local<>(s, Empty.INSTANCE, List.of());
                    }, ""))));
            engine = new TimelineEngine<>("test", definitions, (_, event) -> definitions.stream()
                    .filter(r -> r.eventType().equals(event.signal().type())).map(r -> new RuleBinding(r.definition(), r.definition(), Empty.INSTANCE)).toList(),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1);
            session = new EffectSession(engine, EffectState.empty().withResource(new ResourceState(KEY, 1, 1, 0)), request -> execute(request, failure));
        }
        private ActionResult execute(WorldRequest request, Failure failure) {
            calls.add(request); var token = session.operation(request); capabilities.add(token);
            int phase = ((Phase) request.command()).number();
            var before = session.operationState(token); var domain = before.engine().domain();
            assertEquals(phase, request.id().invocation());
            assertThrows(IllegalStateException.class, () -> session.start(0, INPUT));
            assertThrows(IllegalStateException.class, () -> engine.checkpoint(before));
            if (phase == 1) {
                assertEquals(.6, domain.resources().get(KEY).value(), 1e-12);
                assertEquals(1, before.receipts().size()); assertEquals(2, before.engine().facts().size());
                assertThrows(IllegalStateException.class, () -> session.operationState(capabilities.getFirst()));
                assertThrows(IllegalStateException.class, () -> session.operation(calls.getFirst()));
                if (failure == Failure.BEFORE_WRITE) throw new IllegalStateException("second world outcome unknown before domain publication");
            }
            var paid = Resources.spend(domain.resources().get(KEY), phase == 0 ? "payment" : "second", phase == 0 ? .4 : .3);
            ActionResult result = phase == 0 ? paid : new Value(1);
            var update = session.prepareOperationDomain(token, before, domain.withResource(paid.after()), result, List.of(FACT));
            EffectSession.commitDomains(List.of(update));
            assertEquals(Optional.of(result), token.committed());
            assertThrows(IllegalStateException.class, () -> EffectSession.commitDomains(List.of(update)));
            if (phase == 1 && failure == Failure.UNKNOWN) throw new IllegalStateException("second world outcome unknown after publication");
            if (phase == 1 && failure == Failure.DIFFERENT) return new Value(99);
            return new WorldReceipt(result, List.of(FACT));
        }
    }

    @Test void liveHostExecutesEachPhaseOnceWithFreshCapabilitiesAndSettlesAllFactsBeforeCheckpoint() {
        var f = new Fixture(Failure.NONE); f.session.start(0, INPUT);
        assertTrue(f.session.state().idle()); assertEquals(2, f.calls.size()); assertEquals(5, f.reactions.size());
        assertEquals(2, f.calls.stream().map(WorldRequest::id).distinct().count());
        assertEquals(1, f.calls.stream().map(r -> r.id().frame()).distinct().count());
        assertEquals(1, f.calls.stream().map(r -> r.id().pc()).distinct().count());
        assertEquals(2, f.session.state().receipts().size()); assertEquals(5, f.reactions.stream().map(Event::id).distinct().count());
        assertEquals(1, f.reactions.stream().map(Event::root).distinct().count());
        var saved = f.engine.checkpoint(f.session.state()); var restored = f.engine.restore(saved);
        assertEquals(f.session.state().engine().domain(), restored.engine().domain());
        assertEquals(f.session.state().engine().nextFrame(), restored.engine().nextFrame());
        assertTrue(restored.receipts().isEmpty());
        for (var token : f.capabilities) assertThrows(IllegalStateException.class, () -> f.session.operationState(token));
    }

    @Test void unknownSecondWorldOutcomeKeepsBothPublishedBalancesAndTheSecondPendingCommand() { frozen(Failure.UNKNOWN, .3, 3); }
    @Test void differentSecondReceiptDoesNotUndoPublicationOrRestartEarlierWorldPhases() { frozen(Failure.DIFFERENT, .3, 3); }
    @Test void failureBeforeSecondPublicationStillKeepsTheFirstPaymentAndItsFacts() { frozen(Failure.BEFORE_WRITE, .6, 2); }
    private static void frozen(Failure failure, double balance, int facts) {
        var f = new Fixture(failure); assertThrows(IllegalStateException.class, () -> f.session.start(0, INPUT));
        var state = f.session.state(); assertFalse(f.session.running()); assertEquals(2, f.calls.size()); assertTrue(f.reactions.isEmpty());
        assertEquals(balance, state.engine().domain().resources().get(KEY).value(), 1e-12);
        assertEquals(Optional.of(f.calls.getLast().id()), state.engine().pending());
        assertEquals(Optional.of(new Phase(1)), state.engine().frames().getFirst().pendingCommand());
        assertEquals(1, state.receipts().size()); assertTrue(state.receipts().containsKey(f.calls.getFirst().id()));
        assertEquals(facts, state.engine().facts().size()); assertTrue(state.engine().frames().getFirst().retainedResults().containsKey("payment"));
        assertThrows(IllegalStateException.class, () -> f.engine.checkpoint(state));
        assertThrows(IllegalStateException.class, () -> f.session.start(1, INPUT));
        assertThrows(IllegalStateException.class, f.session::drain);
        assertEquals(2, f.calls.size()); assertSame(state, f.session.state());
    }
}
