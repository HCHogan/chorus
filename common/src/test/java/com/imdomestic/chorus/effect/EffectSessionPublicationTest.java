package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.rule.TimelineEngine;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EffectSessionPublicationTest {
    record Command() implements WorldCommand {}
    static TimelineEngine<EffectState> engine() {
        var rule = new EventRule<EffectState>("call", "call", (_, _) -> true,
                List.of(new Instruction<>((_, _) -> new Await<>(new Command()), "")));
        return new TimelineEngine<>("test", List.of(rule), (_, event) -> event.signal().type().equals("call")
                ? List.of(new RuleBinding("call", "call", Empty.INSTANCE)) : List.of(), EffectCheckpointTest.clock(), 1);
    }
    static EffectSession session() { return new EffectSession(engine(), EffectState.empty(), _ -> Empty.INSTANCE); }
    static EffectSession.DomainUpdate pvp(EffectSession session) {
        var before = session.state(); return session.prepareDomain(before, before.engine().domain().withMode(EffectState.Mode.PVP));
    }
    @Test void publicationPreservesReceiptsCountersAndFutureOperationIdentity() {
        var requests = new ArrayList<WorldRequest>();
        var session = new EffectSession(engine(), EffectState.empty(), request -> { requests.add(request); return Empty.INSTANCE; });
        session.start(0, new Signal("call", Empty.INSTANCE)); var before = session.state();
        var update = pvp(session); assertSame(before, session.state()); EffectSession.commitDomains(List.of(update));
        var after = session.state(); assertEquals(1, requests.size()); assertEquals(EffectState.Mode.PVP, after.engine().domain().mode());
        assertEquals(before.engine().nextEvent(), after.engine().nextEvent()); assertEquals(before.engine().nextFrame(), after.engine().nextFrame());
        assertEquals(before.receipts(), after.receipts()); assertFalse(after.receipts().isEmpty());
        assertEquals(before.engine().receipts(), after.engine().receipts());
        assertEquals(after, engine().transition(after, new Completed(requests.getFirst().id(), Empty.INSTANCE)).state());
        assertThrows(IllegalStateException.class, () -> EffectSession.commitDomains(List.of(update)));
        session.start(0, new Signal("call", Empty.INSTANCE)); assertEquals(2, requests.size());
        assertTrue(requests.getLast().id().frame() > requests.getFirst().id().frame());
    }
    @Test void changedSecondParticipantCannotPartiallyPublishTheFirstEvenWhenItsDomainIsEqual() {
        var a = session(); var b = session(); var first = pvp(a); var second = pvp(b); var beforeA = a.state(); var beforeB = b.state();
        b.observe(0, List.of()); assertEquals(beforeB.engine().domain(), b.state().engine().domain());
        assertThrows(IllegalStateException.class, () -> EffectSession.commitDomains(List.of(first, second)));
        assertSame(beforeA, a.state()); assertEquals(EffectState.Mode.PVE, b.state().engine().domain().mode());
        EffectSession.commitDomains(List.of(first, pvp(b)));
        assertEquals(EffectState.Mode.PVP, a.state().engine().domain().mode()); assertEquals(EffectState.Mode.PVP, b.state().engine().domain().mode());
    }
    @Test void duplicateParticipantsAndForeignThreadsCannotPublishAnyWrite() throws Exception {
        var a = session(); var b = session(); var first = pvp(a); var second = pvp(b); var beforeA = a.state(); var beforeB = b.state();
        assertThrows(IllegalArgumentException.class, () -> EffectSession.commitDomains(List.of(first, second, first)));
        assertSame(beforeA, a.state()); assertSame(beforeB, b.state());
        var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> { try { EffectSession.commitDomains(List.of(first, second)); } catch (Throwable error) { failure.set(error); } });
        worker.start(); worker.join(); assertInstanceOf(IllegalStateException.class, failure.get());
        assertSame(beforeA, a.state()); assertSame(beforeB, b.state());
        EffectSession.commitDomains(List.of(first, second)); assertEquals(EffectState.Mode.PVP, a.state().engine().domain().mode());
    }
    @Test void pendingOrFailedWorldWorkCannotBeErasedByAHostPublication() {
        var a = session(); var first = pvp(a); var beforeA = a.state(); var target = new AtomicReference<EffectSession>();
        var prepared = new AtomicReference<EffectSession.DomainUpdate>(); var calls = new AtomicInteger();
        var b = new EffectSession(engine(), EffectState.empty(), _ -> {
            calls.incrementAndGet(); assertTrue(target.get().running());
            assertThrows(IllegalStateException.class, () -> EffectSession.commitDomains(List.of(first, prepared.get())));
            assertThrows(IllegalStateException.class, () -> pvp(target.get())); assertSame(beforeA, a.state());
            throw new IllegalStateException("world result is unknown");
        });
        target.set(b); prepared.set(pvp(b)); assertThrows(IllegalStateException.class, () -> b.start(0, new Signal("call", Empty.INSTANCE)));
        assertFalse(b.running()); assertTrue(b.state().engine().pending().isPresent());
        assertThrows(IllegalStateException.class, () -> pvp(b));
        assertThrows(IllegalStateException.class, () -> EffectSession.commitDomains(List.of(first, prepared.get())));
        assertEquals(1, calls.get()); assertSame(beforeA, a.state());
    }
    @Test void preparationCannotMoveTheClockOrIntroduceAnAlreadyDueDeadline() {
        var session = session(); var before = session.state();
        var future = before.engine().domain().withBuffs(new BuffStore(1, 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> session.prepareDomain(before, future));
        var timer = new EffectState.Timer("due", 0, 0, 1, new Signal("call", Empty.INSTANCE), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> session.prepareDomain(before, before.engine().domain().schedule(timer)));
        assertSame(before, session.state());
    }
    @Test void futureRulesSeeBothPublishedDomainsAndKeepTheirOriginalDeadlines() {
        var calls = new AtomicInteger(); var sessions = new ArrayList<EffectSession>();
        var initial = EffectState.empty().schedule(new EffectState.Timer("later", 10, 0, 1, new Signal("call", Empty.INSTANCE), Optional.empty()));
        for (int i = 0; i < 2; i++) sessions.add(new EffectSession(engine(), initial, _ -> {
            sessions.forEach(value -> assertEquals(EffectState.Mode.PVP, value.state().engine().domain().mode()));
            calls.incrementAndGet(); return Empty.INSTANCE;
        }));
        EffectSession.commitDomains(sessions.stream().map(EffectSessionPublicationTest::pvp).toList());
        assertEquals(0, calls.get());
        sessions.forEach(value -> { assertEquals(initial.timers(), value.state().engine().domain().timers()); value.observe(9, List.of()); });
        assertEquals(0, calls.get()); sessions.forEach(value -> value.observe(10, List.of())); assertEquals(2, calls.get());
        sessions.forEach(value -> value.observe(20, List.of())); assertEquals(2, calls.get());
    }
    @Test void pendingCallerAndSettledRecipientAreBothPreflightedBeforeAnOperationCommits() {
        var recipient = session(); var caller = new AtomicReference<EffectSession>();
        var session = new EffectSession(engine(), EffectState.empty(), request -> {
            var source = caller.get(); var token = source.operation(request); var before = source.state();
            var first = source.prepareOperationDomain(token, before, before.engine().domain().withMode(EffectState.Mode.PVP), Empty.INSTANCE);
            var second = pvp(recipient); recipient.observe(0, List.of());
            assertThrows(IllegalStateException.class, () -> EffectSession.commitDomains(List.of(first, second)));
            assertSame(before, source.state()); assertTrue(token.committed().isEmpty()); assertEquals(EffectState.Mode.PVE, recipient.state().engine().domain().mode());
            EffectSession.commitDomains(List.of(first, pvp(recipient)));
            assertEquals(before.engine().pending(), source.state().engine().pending()); assertEquals(before.engine().frames(), source.state().engine().frames());
            assertEquals(before.engine().nextEvent(), source.state().engine().nextEvent()); assertEquals(Optional.of(Empty.INSTANCE), token.committed());
            return Empty.INSTANCE;
        });
        caller.set(session); session.start(0, new Signal("call", Empty.INSTANCE));
        assertTrue(session.state().idle()); assertEquals(EffectState.Mode.PVP, session.state().engine().domain().mode());
        assertEquals(EffectState.Mode.PVP, recipient.state().engine().domain().mode());
    }
    @Test void worldOperationCapabilityCannotEscapeItsCallbackOrChangeTheClock() {
        var caller = new AtomicReference<EffectSession>(); var previous = new AtomicReference<EffectSession.WorldOperation>();
        var session = new EffectSession(engine(), EffectState.empty(), request -> {
            var source = caller.get(); var token = source.operation(request); var before = source.state();
            if (previous.get() != null) assertThrows(IllegalStateException.class, () -> source.operationState(previous.get()));
            var later = before.engine().domain().withBuffs(new BuffStore(1, 1, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> source.prepareOperationDomain(token, before, later, Empty.INSTANCE));
            assertSame(before, source.state()); assertTrue(token.committed().isEmpty()); previous.set(token); return Empty.INSTANCE;
        });
        caller.set(session); session.start(0, new Signal("call", Empty.INSTANCE));
        assertThrows(IllegalStateException.class, () -> session.operationState(previous.get()));
        session.start(0, new Signal("call", Empty.INSTANCE)); assertTrue(session.state().idle());
    }
}
