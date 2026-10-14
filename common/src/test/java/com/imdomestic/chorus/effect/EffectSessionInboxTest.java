package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.rule.RuleEngine.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.data.Action;
import com.imdomestic.chorus.effect.data.Condition;
import com.imdomestic.chorus.rule.TimelineEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class EffectSessionInboxTest {
    record Observe(Event event) implements WorldCommand {}
    static CompiledEffects program() {
        var action = new Action() {
            public ResultShape validate(Validation ignored) { return ResultShape.EMPTY; }
            public Outcome<EffectState> execute(Evaluation e) { return new Await<>(new Observe(e.context().event())); }
        };
        var rules = List.of("test:queued", "test:input").stream().map(type -> new EffectProgram.Rule(type.substring(5), type,
                new Condition.Constant(true), List.<EffectProgram.Step>of(new EffectProgram.Instruction(action, "")))).toList();
        return new CompiledEffects(new EffectProgram("inbox", List.of(), List.of(new EffectProgram.Bundle("test:observer", EffectProgram.Scope.SOURCE, rules, List.of())), List.of()));
    }
    static EffectState initial(long time) { return ResourceAuthorityTest.empty(time).withSource(EffectTestSupport.source("test:observer")); }
    static void enqueue(EffectSession session, List<Signal> facts) {
        var before = session.state(); EffectSession.commitDomains(List.of(session.prepareInboxDomain(before, before.engine().domain().withMode(EffectState.Mode.PVP), facts)));
    }
    @Test void inboxCommitsWithoutCallbacksThenRunsAtOriginalTimeBeforeNewInput() {
        var seen = new ArrayList<Event>(); var session = new EffectSession(program(), EffectCheckpointTest.clock(), 1, initial(10), request -> {
            seen.add(((Observe) request.command()).event()); return Empty.INSTANCE;
        });
        var before = session.state(); enqueue(session, List.of(new Signal("test:queued", Empty.INSTANCE), new Signal("test:queued", Empty.INSTANCE)));
        assertTrue(seen.isEmpty()); assertTrue(session.inboxPending()); assertEquals(EffectState.Mode.PVP, session.state().engine().domain().mode());
        assertEquals(before.engine().nextEvent() + 2, session.state().engine().nextEvent());
        assertThrows(IllegalStateException.class, session::checkpoint);
        session.start(20, new Signal("test:input", Empty.INSTANCE)); assertEquals(3, seen.size());
        assertEquals(List.of(10L, 10L, 20L), seen.stream().map(Event::timeMicros).toList());
        assertEquals(Optional.of(seen.getFirst().id()), seen.get(1).parent()); assertEquals(seen.getFirst().root(), seen.get(1).root());
        assertFalse(session.inboxPending()); assertTrue(session.state().idle()); assertEquals(20, session.checkpoint().timeline().timeMicros());
    }
    @Test void invalidInputOrStalePublicationDoesNotConsumeOrEraseTheInbox() {
        var seen = new ArrayList<Event>(); var session = new EffectSession(program(), EffectCheckpointTest.clock(), 1, initial(10), request -> { seen.add(((Observe) request.command()).event()); return Empty.INSTANCE; });
        var old = session.prepareInboxDomain(session.state(), session.state().engine().domain(), List.of(new Signal("test:queued", Empty.INSTANCE)));
        enqueue(session, List.of(new Signal("test:queued", Empty.INSTANCE))); var queued = session.state();
        assertThrows(IllegalStateException.class, () -> EffectSession.commitDomains(List.of(old)));
        assertThrows(IllegalStateException.class, () -> session.prepareDomain(queued, queued.engine().domain()));
        assertThrows(IllegalArgumentException.class, () -> session.start(9, new Signal("test:input", Empty.INSTANCE)));
        assertThrows(IllegalArgumentException.class, () -> session.start(10, new Signal("chorus:internal/advance_time", Empty.INSTANCE)));
        assertSame(queued, session.state()); assertTrue(seen.isEmpty()); session.drain(); assertEquals(1, seen.size());
        session.drain(); assertEquals(1, seen.size());
    }
    @Test void inboxBoundariesRetireReceiptsWithoutResettingOperationOrEventIdentities() {
        var requests = new ArrayList<WorldRequest>(); var session = new EffectSession(program(), EffectCheckpointTest.clock(), 1, initial(0), request -> { requests.add(request); return Empty.INSTANCE; });
        session.start(0, new Signal("test:input", Empty.INSTANCE)); long previousFrame = requests.getLast().id().frame(); long previousEvent = session.state().engine().nextEvent();
        for (int i = 0; i < 8; i++) {
            enqueue(session, List.of(new Signal("test:queued", Empty.INSTANCE))); session.drain();
            assertTrue(requests.getLast().id().frame() > previousFrame); assertTrue(session.state().engine().nextEvent() > previousEvent);
            assertEquals(1, session.state().receipts().size()); assertEquals(1, session.state().engine().receipts().size());
            previousFrame = requests.getLast().id().frame(); previousEvent = session.state().engine().nextEvent();
        }
        assertEquals(9, requests.size()); session.checkpoint();
    }
    @Test void inboxWorldFailureIsNotRetriedAndKeepsTheCommittedDomain() {
        int[] calls = {0}; var session = new EffectSession(program(), EffectCheckpointTest.clock(), 1, initial(0), _ -> { calls[0]++; throw new IllegalStateException("unknown effect"); });
        enqueue(session, List.of(new Signal("test:queued", Empty.INSTANCE))); assertThrows(IllegalStateException.class, session::drain);
        assertEquals(1, calls[0]); assertEquals(EffectState.Mode.PVP, session.state().engine().domain().mode());
        assertTrue(session.state().engine().pending().isPresent()); assertFalse(session.inboxPending());
        assertThrows(IllegalStateException.class, session::drain); assertThrows(IllegalStateException.class, session::checkpoint);
        assertThrows(IllegalStateException.class, () -> session.start(0, new Signal("test:input", Empty.INSTANCE))); assertEquals(1, calls[0]);
    }
    @Test void exhaustedRecipientEventSequenceRejectsPreparationWithoutPublishingAnotherParticipant() {
        var program = program(); var ordinary = new EffectSession(program, EffectCheckpointTest.clock(), 1, initial(0), _ -> Empty.INSTANCE);
        var checkpoint = new EffectSession.Checkpoint(program.program(), new TimelineEngine.Checkpoint<>(program.program().version(), initial(0), 0, Long.MAX_VALUE, 1));
        var exhausted = EffectSession.restore(program, EffectCheckpointTest.clock(), 1, checkpoint, _ -> Empty.INSTANCE);
        var first = ordinary.prepareDomain(ordinary.state(), ordinary.state().engine().domain().withMode(EffectState.Mode.PVP)); var before = ordinary.state();
        assertThrows(ArithmeticException.class, () -> exhausted.prepareInboxDomain(exhausted.state(), exhausted.state().engine().domain(), List.of(new Signal("test:queued", Empty.INSTANCE))));
        assertSame(before, ordinary.state()); assertFalse(exhausted.inboxPending()); assertEquals(Long.MAX_VALUE, exhausted.checkpoint().timeline().nextEvent());
        EffectSession.commitDomains(List.of(first)); assertEquals(EffectState.Mode.PVP, ordinary.state().engine().domain().mode());
    }
}
