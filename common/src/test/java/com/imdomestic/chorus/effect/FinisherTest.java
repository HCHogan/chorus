package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.finisher.Finisher;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class FinisherTest {
    static EffectSource source(String actor) { return new EffectSource(actor, "test:finisher", actor, new BuffInstance.Origin(actor, actor + "/finisher", "", "", Set.of()), Set.of()); }
    static class Harness {
        final CompiledEffects p; EffectSession session;
        final List<Action.CueCommand> cues = new ArrayList<>(); final List<Finisher.Check> checks = new ArrayList<>(); final List<DamageCommand> hits = new ArrayList<>();
        Finisher.Decision decision = Finisher.Decision.ALLOWED; boolean lethal = true, prevented, unknownCheck, unknownDamage;
        Harness() throws Exception { this(link("finisher")); }
        Harness(CompiledEffects p) { this.p = p; session = new EffectSession(p, EffectCheckpointTest.clock(), 1,
                EffectState.empty().withSource(source("a")).withSource(source("b")), this::world); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            if (r.command() instanceof Finisher.Check q) { checks.add(q); if (unknownCheck) throw new IllegalStateException("Unknown qualification"); return new Finisher.Checked(q, decision); }
            if (r.command() instanceof DamageCommand q) { hits.add(q); if (unknownDamage) throw new IllegalStateException("Unknown strike");
                return new DamageReceipt("damage/" + hits.size(), DamageReceipt.Outcome.APPLIED, 0, 0, 10, lethal ? Optional.of("death/" + hits.size()) : Optional.empty(), prevented); }
            var cue = (Action.CueCommand) r.command(); cues.add(cue); return RuleEngine.Empty.INSTANCE;
        }
        EffectState state() { return session.state().engine().domain(); }
        void event(long time, String kind, String actor, String victim) { session.start(time, new RuleEngine.Signal("test:" + kind, new EffectEvent(actor, victim, source(actor).origin(), Set.of(), Map.of()))); }
        void advance(long time) { session.observe(time, List.of()); }
        long count(String cue) { return cues.stream().filter(c -> c.cue().equals("test:" + cue)).count(); }
        void restore() { var checkpoint = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())); session = EffectSession.restore(new CompiledEffects(checkpoint.program()), EffectCheckpointTest.clock(), 1, checkpoint, this::world); }
    }
    @Test void acceptanceReservesBothParticipantsAndCompletionRequiresConfirmedDeath() throws Exception {
        var h = new Harness(); h.event(0, "begin", "a", "target"); assertEquals(1, h.count("started")); assertEquals(0, h.count("completed")); assertTrue(Finisher.busy(h.state(), "a") && Finisher.busy(h.state(), "target"));
        h.advance(299_999); assertTrue(h.hits.isEmpty()); h.advance(300_000);
        assertEquals(1, h.hits.size()); assertEquals(1, h.count("completed")); assertEquals(1, h.count("kill")); assertEquals(0, h.count("cancelled")); assertTrue(h.state().buffs().instances().isEmpty() && h.state().timers().isEmpty());
        assertTrue(h.hits.getFirst().killTags().contains("chorus:finisher_kill")); assertFalse(h.hits.getFirst().tags().contains("chorus:melee"));
    }
    @Test void sharedReservationsRejectOtherActorsAndAnotherTargetWithoutDoubleStarting() throws Exception {
        var h = new Harness(); h.event(0, "begin", "a", "target"); h.event(0, "begin", "b", "target"); h.event(0, "begin", "a", "other"); h.event(0, "begin", "b", "a");
        assertEquals(1, h.count("started")); assertEquals(3, h.count("busy")); assertEquals(1, h.checks.size()); h.advance(300_000); assertEquals(1, h.hits.size());
        h.event(300_001, "begin", "b", "other"); assertEquals(2, h.count("started"));
    }
    @Test void separatePairsProgressIndependently() throws Exception {
        var h = new Harness(); h.event(0, "begin", "a", "one"); h.event(100_000, "begin", "b", "two"); h.advance(300_000); assertEquals(1, h.count("completed")); assertTrue(Finisher.busy(h.state(), "b")); h.advance(400_000); assertEquals(2, h.count("completed"));
    }
    @Test void deniedQualificationDoesNotStartAndKnownCancellationReleasesBothSides() throws Exception {
        for (var decision : Finisher.Decision.values()) if (decision != Finisher.Decision.ALLOWED) {
            var h = new Harness(); h.decision = decision; h.event(0, "begin", "a", "target"); assertEquals(0, h.count("started")); assertTrue(h.state().timers().isEmpty());
            h.decision = Finisher.Decision.ALLOWED; h.event(0, "begin", "a", "target"); h.decision = decision; h.advance(50_000);
            assertEquals(1, h.count("cancelled")); assertTrue(h.hits.isEmpty() && h.state().buffs().instances().isEmpty() && h.state().timers().isEmpty());
        }
    }
    @Test void survivingStrikeAndDeathPreventionNeverProduceCompletion() throws Exception {
        for (boolean prevented : List.of(false, true)) { var h = new Harness(); h.lethal = false; h.prevented = prevented; h.event(0, "begin", "a", "target"); h.advance(300_000);
            assertEquals(1, h.hits.size()); assertEquals(0, h.count("completed")); assertEquals(0, h.count("kill")); assertEquals(1, h.count("cancelled")); assertTrue(h.state().buffs().instances().isEmpty()); }
    }
    @Test void interruptingALockStopsStrikeAndReleasesThePeer() throws Exception {
        var h = new Harness(); h.event(0, "begin", "a", "target"); h.event(70_000, "interrupt", "a", "target"); h.advance(100_000);
        assertEquals(1, h.count("cancelled")); assertTrue(h.state().buffs().instances().isEmpty()); h.advance(400_000); assertTrue(h.hits.isEmpty());
    }
    @Test void explicitCancellationIsImmediateAndDoesNotReleaseAnUnrelatedPair() throws Exception {
        var h = new Harness(); h.event(0, "begin", "a", "one"); h.event(0, "begin", "b", "two"); h.event(1, "cancel", "a", "one");
        assertFalse(Finisher.busy(h.state(), "a")); assertFalse(Finisher.busy(h.state(), "one")); assertTrue(Finisher.busy(h.state(), "b"));
        h.event(2, "cancel", "a", "one"); assertEquals(1, h.count("cancelled")); h.advance(300_000); assertEquals(1, h.count("completed")); assertEquals("two", h.hits.getFirst().target());
    }
    @Test void anInterruptedOldPlanCannotRemoveTheActorsNewReservation() throws Exception {
        var h = new Harness(); h.event(0, "begin", "a", "old"); h.event(10_000, "interrupt", "a", "old"); h.event(20_000, "begin", "a", "new");
        assertEquals(2, h.count("started")); h.advance(50_000); assertEquals(1, h.count("cancelled"));
        assertFalse(Finisher.busy(h.state(), "old")); assertTrue(Finisher.busy(h.state(), "a") && Finisher.busy(h.state(), "new"));
        h.advance(320_000); assertEquals(1, h.count("completed")); assertEquals("new", h.hits.getFirst().target()); assertTrue(h.state().buffs().instances().isEmpty());
    }
    @Test void checkpointsRetainReservationOriginAndDeadlineAcrossSourceUnbinding() throws Exception {
        var h = new Harness(); h.event(0, "begin", "a", "target"); h.advance(125_000); var before = h.state(); h.restore(); assertEquals(before, h.state());
        h.session.start(125_000, SourceChange.remove("a")); h.advance(300_000);
        assertEquals(1, h.hits.size()); assertEquals(source("a").origin(), h.hits.getFirst().source()); assertTrue(h.state().buffs().instances().isEmpty());
    }
    @Test void unknownAuthorizationOrStrikeDoesNotRetryOrInventCompletion() throws Exception {
        var h = new Harness(); h.unknownCheck = true; assertThrows(IllegalStateException.class, () -> h.event(0, "begin", "a", "target")); assertEquals(0, h.count("started")); assertTrue(h.state().buffs().instances().isEmpty());
        var struck = new Harness(); struck.event(0, "begin", "a", "target"); struck.unknownDamage = true; assertThrows(IllegalStateException.class, () -> struck.advance(300_000)); assertEquals(1, struck.hits.size()); assertEquals(0, struck.count("completed"));
        assertThrows(IllegalStateException.class, () -> struck.advance(400_000)); assertEquals(1, struck.hits.size());
    }
    @Test void invalidPoliciesAndNonSharedLocksFailBeforeExecution() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Finisher.Policy(0, 2, "test:eligible", true));
        assertThrows(IllegalArgumentException.class, () -> new Finisher.Policy(1.1, 2, "test:eligible", true));
        var data = json("finisher"); data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("duration", 1);
        assertThrows(IllegalStateException.class, () -> EffectCodecs.COMPILED.parse(com.mojang.serialization.JsonOps.INSTANCE, data).getOrThrow());
    }
}
