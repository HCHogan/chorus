package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuffApplicationTest {
    static StatusApplicationTest.Harness harness() throws Exception {
        return new StatusApplicationTest.Harness(link("suppression", "buff_application"));
    }
    @Test void directGrantsAndAuthorizedStatusesCreditTheCurrentApplicantWhileRetainingTheFirstOrigin() throws Exception {
        for (String first : List.of("grant", "apply")) for (String second : List.of("grant", "apply")) {
            var h = harness(); h.event(0, first, "a"); var old = h.buff(); h.event(1_000_000, second, "b");
            assertEquals(List.of("a", "b"), h.heals.stream().map(HealingCommand::target).toList());
            assertEquals(StatusApplicationTest.source("b").origin(), h.heals.getLast().source());
            assertEquals(old.origin(), h.buff().origin()); assertEquals(old.generation(), h.buff().generation());
        }
    }
    @Test void refreshCheckRemoveExpiryAndRestorationOfCheckpointDoNotCreateNewGrants() throws Exception {
        var h = harness(); h.event(0, "check", "a"); assertTrue(h.heals.isEmpty());
        h.event(0, "grant", "a"); h.restore(); assertEquals(1, h.heals.size());
        h.event(1, "refresh", "b"); h.event(2, "clear", "b"); assertEquals(1, h.heals.size());
        h.event(3, "apply", "b"); h.session.observe(10_000_003, List.of());
        assertEquals(2, h.heals.size()); assertTrue(h.state().buffs().instances().isEmpty());
    }
    @Test void acceptedNoChangeReapplicationsHaveCreditAndExpiredReapplicationsHaveNewGenerations() throws Exception {
        var h = harness(); h.event(0, "apply", "a"); var first = h.buff(); h.event(0, "grant", "b");
        assertEquals(2, h.heals.size()); assertEquals(1, h.buff().count());
        long expiry = h.buff().deadline(); h.session.observe(expiry, List.of()); assertTrue(h.state().buffs().instances().isEmpty());
        h.event(expiry, "grant", "b");
        assertNotEquals(first.generation(), h.buff().generation()); assertEquals(StatusApplicationTest.source("b").origin(), h.buff().origin());
    }
    @Test void deniedDeadMissingAndUnknownAuthorizationCannotPublishGenericGrantCredit() throws Exception {
        for (var decision : List.of(StatusResult.Decision.DENIED, StatusResult.Decision.DEAD, StatusResult.Decision.MISSING)) {
            var h = harness(); h.decision = decision; h.event(0, "apply", "a");
            assertTrue(h.heals.isEmpty()); assertTrue(h.state().buffs().instances().isEmpty());
        }
        var h = harness(); h.unknown = true; assertThrows(IllegalStateException.class, () -> h.event(0, "apply", "a"));
        assertTrue(h.heals.isEmpty()); assertTrue(h.state().buffs().instances().isEmpty());
        assertThrows(IllegalStateException.class, () -> h.event(1, "apply", "a")); assertEquals(1, h.operations.size());
    }
    @Test void unknownDownstreamWorldResultDoesNotUndoOrReplayTheCommittedGrant() throws Exception {
        for (String action : List.of("grant", "apply")) {
            var h = harness(); h.failHeal = true; assertThrows(IllegalStateException.class, () -> h.event(0, action, "a"));
            assertEquals(1, h.heals.size()); assertEquals(StatusApplicationTest.source("a").origin(), h.buff().origin());
            assertThrows(IllegalStateException.class, () -> h.event(1, action, "a")); assertEquals(1, h.heals.size());
        }
    }
    @Test void factHasStableOperationIdentityReceiptValuesAndExplicitAuthorizationWithoutFabricatedObservations() throws Exception {
        var h = harness(); h.event(0, "apply", "a"); var old = h.state().buffs(); var definition = h.buff().definition();
        var applicant = StatusApplicationTest.source("b").origin(); var result = Buffs.grant(old, definition, "target", "target", applicant, 1, 1, 10_000_000);
        var op = h.operations.getFirst();
        var direct = BuffApplicationFacts.granted(op, old, result, definition, "target", applicant, false);
        var authorized = BuffApplicationFacts.granted(op, old, result, definition, "target", applicant, true);
        var event = (EffectEvent) direct.payload(); var other = (EffectEvent) authorized.payload();
        assertEquals("chorus:buff_applied", direct.type()); assertEquals("b", event.actor()); assertEquals("target", event.victim()); assertEquals(applicant, event.source());
        assertEquals(1, event.numbers().get("stacks_before").value()); assertEquals(1, event.numbers().get("stacks_after").value());
        assertEquals(0, event.numbers().get("stored_delta").value()); assertTrue(event.flags().get("applied")); assertFalse(event.flags().get("new_instance"));
        assertFalse(event.flags().get("status_authorized")); assertTrue(other.flags().get("status_authorized")); assertEquals(event.references(), other.references());
        assertEquals(direct, BuffApplicationFacts.granted(op, old, result, definition, "target", applicant, false));
        assertEquals(Long.toString(h.buff().generation()), event.references().get("buff_generation"));
        assertTrue(event.observedEntities().isEmpty() && event.observedBuffs().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> BuffApplicationFacts.granted(op, old,
                new Buffs.Result(old, new Buffs.Receipt(1, 0, 0, false), List.of()), definition, "target", applicant, false));
    }
    @Test void previouslyQueuedLifecycleCleanupCannotEraseTheConfirmedApplication() throws Exception {
        var data = json("suppression"); data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").add(com.google.gson.JsonParser.parseString("""
            {"id":"cleanse","on":"chorus:buff_gained","if":{"type":"chorus:own_buff"},"do":[
              {"type":"chorus:remove_buff","buff":"chorus_d2:suppression"}]}
            """));
        var p = CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("buff_application")).getOrThrow()));
        var h = new StatusApplicationTest.Harness(p); h.event(0, "grant", "b");
        assertTrue(h.state().buffs().instances().isEmpty()); assertEquals(1, h.heals.size()); assertEquals("b", h.heals.getFirst().target());
    }
}
