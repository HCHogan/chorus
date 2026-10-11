package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class StatusApplicationTest {
    static EffectSource source(String id) { return new EffectSource(id, "test:status_application", id, new BuffInstance.Origin(id, id + "/source", id + "/gun", id + "/ability", Set.of("test:source")), Set.of()); }
    static class Harness {
        final CompiledEffects p; EffectSession session; final List<HealingCommand> heals = new ArrayList<>(); final List<RuleEngine.OperationId> operations = new ArrayList<>();
        StatusResult.Decision decision = StatusResult.Decision.ALLOWED; boolean unknown, failHeal;
        Harness() throws Exception { this(link("suppression", "status_application")); }
        Harness(CompiledEffects program) { p = program; session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withSource(source("a")).withSource(source("b")), this::world); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            if (r.command() instanceof StatusResult.Check q) { operations.add(r.id()); if (unknown) throw new IllegalStateException("unknown authorization"); return new StatusResult.Checked(q, decision); }
            var heal = (HealingCommand) r.command(); heals.add(heal); if (failHeal) throw new IllegalStateException("unknown downstream healing"); return new HealingReceipt(r.id().toString(), heal, HealingReceipt.Outcome.APPLIED, 1, 1, 0);
        }
        EffectState state() { return session.state().engine().domain(); }
        BuffInstance buff() { return state().buffs().instances().values().stream().filter(b -> b.key().definition().equals("chorus_d2:suppression")).findFirst().orElseThrow(); }
        void event(long time, String kind, String actor) { var s = source(actor); session.start(time, new RuleEngine.Signal("test:" + kind, new EffectEvent(actor, "target", s.origin(), Set.of(), Map.of(), Map.of(), Map.of("damage_id", "incoming/" + time)))); assertTrue(session.state().idle() && session.state().engine().failure().isEmpty()); }
        void restore() { var c = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())); session = EffectSession.restore(new CompiledEffects(c.program()), EffectCheckpointTest.clock(), 1, c, this::world); }
    }
    @Test void differentApplicantsReceiveTheirOwnCreditWhileTargetStatusKeepsItsFirstOrigin() throws Exception {
        var h = new Harness(); h.event(0, "apply", "a"); var first = h.buff(); h.event(1_000_000, "apply", "b");
        assertEquals(List.of("a", "b"), h.heals.stream().map(HealingCommand::target).toList()); assertEquals(source("b").origin(), h.heals.getLast().source());
        assertEquals(first.origin(), h.buff().origin()); assertEquals(first.generation(), h.buff().generation()); assertEquals(11_000_000, h.buff().deadline());
    }
    @Test void acceptedReapplicationWithoutExtraStacksOrTimeStillHasDistinctApplicationCredit() throws Exception {
        var h = new Harness(); h.event(0, "apply", "a"); h.event(0, "apply", "b"); assertEquals(1, h.buff().count()); assertEquals(10_000_000, h.buff().deadline()); assertEquals(2, h.heals.size()); assertNotEquals(h.operations.getFirst(), h.operations.getLast());
    }
    @Test void deniedDeadAndMissingStatusesNeitherCreateCreditNorReplaceExistingTargetState() throws Exception {
        for (var decision : List.of(StatusResult.Decision.DENIED, StatusResult.Decision.DEAD, StatusResult.Decision.MISSING)) { var h = new Harness(); h.decision = decision; h.event(0, "apply", "a"); assertTrue(h.heals.isEmpty()); assertTrue(h.state().buffs().instances().isEmpty());
            h.decision = StatusResult.Decision.ALLOWED; h.event(1, "apply", "a"); var before = h.buff(); h.decision = decision; h.event(2, "apply", "b"); assertEquals(before, h.buff()); assertEquals(1, h.heals.size()); }
    }
    @Test void PureGrantCheckRefreshAndRemoveDoNotImpersonateAnAuthorizedStatusApplication() throws Exception {
        var h = new Harness(); h.event(0, "check", "a"); h.event(0, "grant", "a"); h.event(1, "refresh", "b"); h.event(2, "clear", "b"); assertTrue(h.heals.isEmpty()); assertTrue(h.state().buffs().instances().isEmpty()); assertEquals(1, h.operations.size());
    }
    @Test void applicationFactCarriesStableOperationIdentityCurrentApplicantInstanceAndOriginalDamageCause() throws Exception {
        var h = new Harness(); h.event(0, "apply", "a"); var buff = h.buff(); var request = new StatusResult.Check("target", buff.definition(), source("b").origin(), 1, 1, 10_000_000);
        var receipt = new StatusResult(Optional.of("damage/42"), true, 1, 1, Optional.of(new EffectState.Lifetime(buff.key(), buff.generation())), StatusResult.Decision.ALLOWED);
        var signal = StatusFacts.applied(h.operations.getFirst(), request, receipt); var e = (EffectEvent) signal.payload(); assertEquals("chorus:status_applied", signal.type()); assertEquals("b", e.actor()); assertEquals("target", e.victim()); assertEquals(source("b").origin(), e.source());
        assertEquals("damage/42", e.references().get("damage_id")); assertEquals("chorus_d2:suppression", e.references().get("buff_definition")); assertEquals(Long.toString(buff.generation()), e.references().get("buff_generation")); assertFalse(e.flags().get("new_instance")); assertEquals(1, e.numbers().get("stacks_before").value());
        assertEquals(signal, StatusFacts.applied(h.operations.getFirst(), request, receipt)); assertTrue(e.observedBuffs().isEmpty() && e.observedEntities().isEmpty(), "no world observations fabricated by authorization");
        var invalid = new StatusResult(Optional.empty(), false, 1, 1, Optional.empty(), StatusResult.Decision.DENIED); assertThrows(IllegalArgumentException.class, () -> StatusFacts.applied(h.operations.getFirst(), request, invalid));
    }
    @Test void expiryAndCheckpointPreserveFirstOriginButEachNewApplicationKeepsItsActualApplicant() throws Exception {
        var h = new Harness(); h.event(0, "apply", "a"); var first = h.buff(); h.restore(); h.event(1_000_000, "apply", "b"); assertEquals(first.origin(), h.buff().origin()); assertEquals(2, h.heals.size()); assertNotEquals(h.operations.getFirst(), h.operations.getLast());
        h.event(11_000_000, "apply", "b"); assertNotEquals(first.generation(), h.buff().generation()); assertEquals(source("b").origin(), h.buff().origin()); assertEquals(3, h.heals.size());
    }
    @Test void unknownAuthorizationDoesNotGrantStatusOrCreditAndCannotBeRetriedAutomatically() throws Exception {
        var h = new Harness(); h.unknown = true; assertThrows(IllegalStateException.class, () -> h.event(0, "apply", "a")); assertTrue(h.state().buffs().instances().isEmpty()); assertTrue(h.heals.isEmpty()); assertThrows(IllegalStateException.class, () -> h.event(1, "apply", "a")); assertEquals(1, h.operations.size());
    }
    @Test void downstreamUnknownResultKeepsCommittedStatusAndDoesNotRepeatItsApplication() throws Exception {
        var h = new Harness(); h.failHeal = true; assertThrows(IllegalStateException.class, () -> h.event(0, "apply", "a")); assertEquals(source("a").origin(), h.buff().origin()); assertEquals(1, h.heals.size()); assertThrows(IllegalStateException.class, () -> h.event(1, "apply", "a")); assertEquals(1, h.operations.size());
    }
    @Test void earlierLifecycleReactionMayRemoveTheStateButCannotEraseItsConfirmedApplicationCredit() throws Exception {
        var data = json("suppression"); data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").add(com.google.gson.JsonParser.parseString("""
            {"id":"immediate_cleanse","on":"chorus:buff_gained","if":{"type":"chorus:own_buff"},"do":[
              {"type":"chorus:remove_buff","buff":"chorus_d2:suppression"}
            ]}
            """));
        var programs = new ArrayList<EffectProgram>();
        for (var part : List.of(data, json("status_application"))) programs.add(EffectCodecs.PROGRAM.parse(com.mojang.serialization.JsonOps.INSTANCE, part).getOrThrow());
        var h = new Harness(CompiledEffects.link(programs)); h.event(0, "apply", "a"); assertTrue(h.state().buffs().instances().isEmpty()); assertEquals(1, h.heals.size()); assertEquals("a", h.heals.getFirst().target());
    }
}
