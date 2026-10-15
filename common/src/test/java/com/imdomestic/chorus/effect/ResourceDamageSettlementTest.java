package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.CombatParticipantQueryTest.*;
import static com.imdomestic.chorus.effect.ResourceDamagePreparationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceDamageSettlementTest {
    static DamageReceipt raw(String id) { return new DamageReceipt(id, DamageReceipt.Outcome.APPLIED, 0, 0, 10, Optional.empty(), false); }
    static boolean charged(EffectState state) { return state.buffs().instances().keySet().stream().anyMatch(key -> key.definition().equals("test:next_hit")); }
    static List<RuleEngine.Event> queuedLifecycle(Fixture f, String domain) {
        return f.sessions.get(domain).state().engine().facts().stream().filter(event -> event.signal().payload() instanceof Buffs.Change).toList();
    }
    static DamageReceipt completed(Fixture f) {
        return f.routes.stream().filter(ActionRoutes.Completed.class::isInstance).map(ActionRoutes.Completed.class::cast)
                .map(ActionRoutes.Completed::value).filter(DamageReceipt.class::isInstance).map(DamageReceipt.class::cast).findFirst().orElseThrow();
    }
    static void drain(Fixture f) { assertFalse(f.host.drain(100).pending()); }
    static Fixture observing() throws Exception { return new Fixture(steps(damage()), true, true); }
    static List<String> trace(Fixture f) { return f.traces.stream().map(value -> value.domain() + "/" + value.cause().type()).toList(); }
    static List<RuleEngine.Signal> ended(Fixture f, String domain) {
        return f.traces.stream().filter(value -> value.domain().equals(domain) && value.cause().type().equals("chorus:buff_ended")).map(CueTrace::cause).toList();
    }

    @Test void ordinaryWorldReceiptConsumesThePinnedRemoteBuffAndPreservesItsOriginalDamageEvidence() throws Exception {
        var f = observing(); var raw = raw("actual/world/damage/42"); f.damageResult = (_, _) -> raw;
        var a = f.state(A); var b = f.state(B); f.start();
        assertFalse(charged(f.state(C))); assertTrue(f.state(D).buffs().instances().isEmpty());
        assertSame(a, f.state(A)); assertSame(b, f.state(B));
        var result = completed(f); assertTrue(result.consumptionSettled());
        assertEquals(raw.damageId(), result.damageId()); assertEquals(raw.healthLoss(), result.healthLoss());
        assertTrue(result.consumptionFacts().orElseThrow().isEmpty(), "remote lifecycle belongs to its own inbox");
        assertEquals(2, result.observedBuffs().orElseThrow().require(OWNER).size(), "observation precedes one-use consumption");
        assertTrue(f.sessions.get(D).state().engine().receipts().containsValue(raw), "the raw world result stays the completion proof");
        assertTrue(queuedLifecycle(f, D).isEmpty()); assertFalse(queuedLifecycle(f, C).isEmpty());
        var expected = queuedLifecycle(f, C).stream().map(RuleEngine.Event::signal).toList(); drain(f);
        assertEquals(expected, ended(f, C));
        assertEquals(List.of(D + "/chorus:hit", D + "/chorus:damage_taken", C + "/chorus:buff_ended"), trace(f));
        var delivered = List.copyOf(f.traces); drain(f); assertEquals(delivered, f.traces);
        assertEquals(1, f.damages.size()); assertDoesNotThrow(f.host::checkpoint);
    }

    @Test void callerLifecycleIsEmittedAfterDamageFactsAndIsNotPublishedTwice() throws Exception {
        var f = observing(); f.host.commit(f.host.prepareTransferWithBuffs(OWNER, C, D));
        f.damageResult = (_, _) -> raw("local/actual"); f.start(); drain(f);
        var result = completed(f); assertFalse(result.consumptionFacts().orElseThrow().isEmpty());
        assertEquals(List.of(D + "/chorus:hit", D + "/chorus:damage_taken", D + "/chorus:buff_ended"), trace(f));
        assertEquals(result.consumptionFacts().orElseThrow(), ended(f, D));
        assertTrue(ended(f, C).isEmpty());
        var delivered = List.copyOf(f.traces); drain(f); assertEquals(delivered, f.traces);
        assertFalse(charged(f.state(D))); assertDoesNotThrow(f.host::checkpoint);
    }

    @Test void anAlreadyCommittedNativeReceiptPassesThroughWithoutRepeatingConsumptionOrRemoteFacts() throws Exception {
        var f = observing(); var receipt = new DamageReceipt[1]; var published = new ArrayList<RuleEngine.Signal>();
        f.damageResult = (request, damage) -> {
            var boundary = f.host.beginCombat(D, Optional.of(request)); var actual = raw("native/actual");
            var claim = CombatDomains.capture(boundary.worlds(), boundary.placement(), D, actual.damageId(), damage);
            var settled = CombatDomains.finish(boundary.worlds(), boundary.placement(), claim, actual);
            f.host.commitCombat(boundary, settled.worlds(), Optional.of(settled.receipt()), settled.factsByDomain());
            receipt[0] = settled.receipt(); published.addAll(settled.factsByDomain().getOrDefault(C, List.of())); return settled.receipt();
        };
        f.start(); drain(f); assertSame(receipt[0], completed(f));
        assertFalse(charged(f.state(C))); assertEquals(published, ended(f, C)); assertEquals(1, published.size());
        assertEquals(List.of(D + "/chorus:hit", D + "/chorus:damage_taken", C + "/chorus:buff_ended"), trace(f));
        var delivered = List.copyOf(f.traces); drain(f); assertEquals(delivered, f.traces);
        assertEquals(1, f.damages.size()); assertDoesNotThrow(f.host::checkpoint);
    }

    @Test void anUnknownWorldResultRetainsThePreparedAuthorityAndCannotBecomeACheckpointOrReplay() throws Exception {
        var f = new Fixture(steps(damage())); f.damageResult = (_, _) -> { throw new IllegalStateException("unknown world result"); };
        assertThrows(IllegalStateException.class, f::start); assertTrue(charged(f.state(C))); assertEquals(1, f.damages.size());
        var error = assertThrows(IllegalStateException.class, f.host::checkpoint);
        assertTrue(error.getMessage().contains("Unresolved coordinated damage preparation"));
        assertThrows(IllegalStateException.class, f::start); assertEquals(1, f.damages.size());
    }

    @Test void aCompletionCallbackFailureKeepsPublishedRemoteConsumptionWithoutReplayingTheWorldWrite() throws Exception {
        var f = new Fixture(steps(damage())); f.damageResult = (_, _) -> raw("committed/actual");
        f.routeReply = receipt -> {
            if (receipt instanceof ActionRoutes.Completed completed && completed.value() instanceof DamageReceipt)
                throw new IllegalStateException("unknown after joint completion");
            return receipt;
        };
        assertThrows(IllegalStateException.class, f::start); assertFalse(charged(f.state(C)));
        assertFalse(queuedLifecycle(f, C).isEmpty()); assertTrue(completed(f).consumptionSettled());
        assertThrows(IllegalStateException.class, f.host::checkpoint); assertThrows(IllegalStateException.class, f::start);
        assertEquals(1, f.damages.size());
    }

    @Test void completionProjectionCannotReplaceDamageLossesOrTheOriginalPreparedLedger() throws Exception {
        var f = new Fixture(steps(damage())); var actual = raw("proven/actual"); f.damageResult = (_, _) -> actual; int[] checked = {0};
        f.beforeRoute = request -> {
            if (!(request.command() instanceof ActionRoutes.Finish finish) || !(finish.command() instanceof DamageCommand)) return;
            var view = f.host.readView(D, request); var state = f.sessions.get(D).state().engine();
            var forged = new DamageReceipt(actual.damageId(), actual.outcome(), 0, 0, 99, Optional.empty(), false)
                    .withObservedBuffs(BuffObservation.capture(view.state().buffs(), OWNER, VICTIM)).withConsumptionFacts(List.of());
            assertThrows(IllegalArgumentException.class, () -> f.program.completeDamageActionRoute(view, view, state, request, forged));
            var replacement = new ActionRoutes.Finish(finish.site(), finish.operation(), finish.command(), forged);
            assertThrows(IllegalArgumentException.class, () -> f.program.requireActionRoute(view, state, new RuleEngine.WorldRequest(request.id(), replacement)));
            assertTrue(charged(f.state(C))); checked[0]++;
        };
        f.start(); drain(f); assertEquals(1, checked[0]); assertFalse(charged(f.state(C))); assertEquals(10, completed(f).healthLoss());
    }
}
