package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SelectedAbilityResourceTest {
    static final EffectSource SOURCE = source("test:control");
    static final class Harness {
        final CompiledEffects p; EffectSession session; final List<HealingCommand> heals = new ArrayList<>();
        Harness() throws Exception {
            p = load("selected_ability_resource"); session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withSource(SOURCE), this::world);
            choose("player", "class", "a"); choose("player", "grenade", "grenade");
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) { var c = (HealingCommand) r.command(); heals.add(c); return HealingReceipt.unapplied(r.id().toString(), c, HealingReceipt.Outcome.MISSING); }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void choose(String who, String slot, String ability) {
            var old = state().abilities().getOrDefault(who, AbilityLoadout.EMPTY); var slots = new HashMap<>(old.slots()); if (ability.isEmpty()) slots.remove("test:" + slot); else slots.put("test:" + slot, "test:" + ability);
            session.start(now(), new AbilityChange(who, old, new AbilityLoadout(slots)).signal());
        }
        void event(String event, Map<String,String> references) { session.start(now(), new RuleEngine.Signal("test:" + event, new EffectEvent("player", "target", SOURCE.origin(), Set.of(), Map.of(), Map.of(), references))); }
        void until(long t) { session.observe(t, List.of()); }
        double energy(String resource) { return state().resources().get(new ResourceState.Key("player", "test:" + resource + "_energy")).value(); }
        Evaluation evaluation(Map<String,String> refs) {
            var event = new RuleEngine.Event(0, 0, Optional.empty(), now(), new RuleEngine.Signal("test:query", new EffectEvent("player", "target", SOURCE.origin(), Set.of(), Map.of(), Map.of(), refs)));
            return new Evaluation(state(), new RuleEngine.Context(event, "test", SOURCE, Map.of()), Map.of(), Map.of(), Map.of(), Map.of(), Optional.of(p));
        }
        void restore() { session = EffectSession.restore(p, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())), this::world); }
    }
    static Condition current(Evaluation.Target target) { return new Condition.SelectedAbilityEventResource("test:class", "resource", target); }
    @Test void regenerationBonusFollowsTheCurrentSelectionAndNeverAllRetainedAccounts() throws Exception {
        var h = new Harness(); h.event("boost", Map.of()); h.until(1_000_000);
        assertEquals(.4, h.energy("a"), 1e-12); assertEquals(.2, h.energy("b"), 1e-12); assertEquals(.3, h.energy("grenade"), 1e-12);
        h.choose("player", "class", "b"); h.until(2_000_000); assertEquals(.5, h.energy("a"), 1e-12); assertEquals(1, h.energy("b"), 1e-12);
        h.choose("player", "class", ""); h.until(3_000_000); assertEquals(.6, h.energy("a"), 1e-12); assertEquals(1.2, h.energy("b"), 1e-12);
        h.choose("player", "class", "a"); h.until(4_000_000); assertEquals(.7, h.energy("a"), 1e-12); assertEquals(1.4, h.energy("b"), 1e-12);
    }
    @Test void additionalBaseRateDoesNotMultiplyTheFinalStatAdjustedRate() throws Exception {
        var h = new Harness(); h.session.start(0, SourceChange.bind(new EffectSource("stat", "test:stat", "player", SOURCE.origin(), Set.of())));
        h.event("boost", Map.of()); h.until(1_000_000); assertEquals(.5, h.energy("a"), 1e-12); assertEquals(.4, h.energy("b"), 1e-12);
    }
    @Test void explicitRechargeAccountWinsOverPaymentWhileCrossSlotPaymentDoesNotCreateAReceivingAlias() throws Exception {
        var h = new Harness(); h.choose("player", "class", "recharge");
        assertFalse(current(Evaluation.Target.SELF).test(h.evaluation(Map.of("resource", "test:a_energy")))); assertTrue(current(Evaluation.Target.SELF).test(h.evaluation(Map.of("resource", "test:b_energy"))));
        h.choose("player", "air", "air"); var air = new Condition.SelectedAbilityEventResource("test:air", "resource", Evaluation.Target.SELF);
        assertFalse(air.test(h.evaluation(Map.of("resource", "test:b_energy"))));
        h.choose("player", "class", "alias"); assertTrue(current(Evaluation.Target.SELF).test(h.evaluation(Map.of("resource", "test:a_energy"))));
        h.choose("player", "class", "free"); assertFalse(current(Evaluation.Target.SELF).test(h.evaluation(Map.of("resource", "test:a_energy"))));
    }
    @Test void holderSlotAndResourceReferenceAreIndependentOfOriginTagsAndStoredBalances() throws Exception {
        var h = new Harness(); h.choose("target", "class", "b");
        assertFalse(current(Evaluation.Target.SELF).test(h.evaluation(Map.of("resource", "test:b_energy")))); assertTrue(current(Evaluation.Target.VICTIM).test(h.evaluation(Map.of("resource", "test:b_energy"))));
        for (var refs : List.of(Map.<String,String>of(), Map.of("resource", ""), Map.of("resource", "wrong"), Map.of("resource", "test:unknown"))) assertFalse(current(Evaluation.Target.SELF).test(h.evaluation(refs)));
        assertTrue(new Condition.SelectedAbilityEventResource("test:class", "receiving", Evaluation.Target.SELF).test(h.evaluation(Map.of("receiving", "test:a_energy"))));
        assertFalse(new Condition.SelectedAbilityResource("test:grenade", "test:a_energy", Evaluation.Target.SELF).test(h.evaluation(Map.of())));
    }
    @Test void castReplacementChangesPaymentAndCreditWithoutChangingTheSelectedReceivingAccount() throws Exception {
        var h = new Harness(); h.session.start(0, SourceChange.bind(new EffectSource("override", "test:override", "player", SOURCE.origin(), Set.of())));
        var request = new AbilityUse.Request("player", "test:class", "replacement", new EffectEvent("player", "player", SOURCE.origin(), Set.of(), Map.of()));
        var receipt = (AbilityUse.Receipt) h.p.useAbility(h.state(), request).result();
        assertEquals(AbilityUse.Outcome.ACCEPTED, receipt.outcome()); assertEquals("test:replacement", receipt.resolved()); h.session.start(0, request.signal());
        assertTrue(current(Evaluation.Target.SELF).test(h.evaluation(Map.of("resource", "test:a_energy")))); assertFalse(current(Evaluation.Target.SELF).test(h.evaluation(Map.of("resource", "test:b_energy"))));
    }
    @Test void snapshotsFreezeTheReferenceAndSourceSelectionButKeepVictimSelectionLive() throws Exception {
        var h = new Harness(); h.choose("target", "class", "a"); var e = h.evaluation(Map.of("resource", "test:a_energy"));
        var own = current(Evaluation.Target.SELF).snapshot(e); var victim = current(Evaluation.Target.VICTIM).snapshot(e);
        assertEquals(new Condition.Constant(true), own); assertEquals(new Condition.SelectedAbilityResource("test:class", "test:a_energy", Evaluation.Target.VICTIM), victim);
        h.choose("player", "class", "b"); h.choose("target", "class", "b"); e = h.evaluation(Map.of("resource", "test:b_energy"));
        assertTrue(own.test(e)); assertFalse(victim.test(e)); h.choose("target", "class", "a"); assertTrue(victim.test(h.evaluation(Map.of())));
        assertEquals(victim, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(victim), Condition.class));
    }
    @Test void detachedContinuationsAndRestoredRateQueriesReadTheLaterCommittedSelection() throws Exception {
        var h = new Harness(); h.event("boost", Map.of()); h.event("delay", Map.of("resource", "test:b_energy")); h.restore(); h.choose("player", "class", "b");
        h.until(100_000); assertEquals(1, h.heals.size()); assertEquals(4, h.heals.getFirst().amount()); assertEquals(.08, h.energy("b"), 1e-12);
        h.event("delay", Map.of("resource", "test:b_energy")); h.session.start(h.now(), SourceChange.remove(SOURCE.instance())); h.choose("player", "class", "a"); h.until(200_000); assertEquals(1, h.heals.size());
    }
    @Test void codecStorageAndProgramRoundtripRejectInvalidOrUnknownDeclarationFields() throws Exception {
        var h = new Harness(); assertEquals(h.p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.p.program()).getOrThrow()).getOrThrow()); h.restore();
        for (Condition c : List.of(new Condition.SelectedAbilityResource("test:class", "test:a_energy", Evaluation.Target.SELF), current(Evaluation.Target.VICTIM))) {
            assertEquals(c, EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, EffectCodecs.CONDITION.encodeStart(JsonOps.INSTANCE, c).getOrThrow()).getOrThrow());
            assertEquals(c, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(c), Condition.class));
        }
        assertThrows(IllegalArgumentException.class, () -> new Condition.SelectedAbilityResource("bad", "test:a_energy", Evaluation.Target.SELF));
        assertThrows(IllegalArgumentException.class, () -> new Condition.SelectedAbilityEventResource("test:class", "", Evaluation.Target.SELF));
        var json = com.google.gson.JsonParser.parseString("{\"type\":\"chorus:selected_ability_event_resource\",\"slot\":\"test:class\",\"resource\":\"test:a_energy\"}");
        assertTrue(EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, json).error().isPresent());
    }
}
