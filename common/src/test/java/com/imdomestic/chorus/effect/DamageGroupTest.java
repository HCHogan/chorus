package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DamageGroupTest {
    static final EffectSource SOURCE = source("test:control");
    static final class Harness {
        final CompiledEffects program; final EffectSession session;
        final List<Double> amounts = new ArrayList<>(); final List<DamageCommand> commands = new ArrayList<>();
        final List<Integer> liveAtIssue = new ArrayList<>();
        DamageReceipt.Outcome first = DamageReceipt.Outcome.APPLIED; int unknownAt = -1;
        Harness() throws Exception { this(json("damage_group")); }
        Harness(JsonObject data) {
            program = compile(data);
            session = new EffectSession(engine(program), EffectState.empty().withSource(SOURCE), request -> {
                var command = (DamageCommand) request.command(); commands.add(command); liveAtIssue.add(state().buffs().instances().size());
                amounts.add(program.outgoing(state(), command, command.amount()).orElseThrow().output().value());
                if (commands.size() == unknownAt) throw new IllegalStateException("Unknown group component outcome");
                var outcome = commands.size() == 1 ? first : DamageReceipt.Outcome.APPLIED;
                return new DamageReceipt(request.id().toString(), outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? amounts.getLast() : 0, Optional.empty(), false);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        void run(String event) { session.start(state().buffs().timeMicros(), new RuleEngine.Signal("test:" + event, event(SOURCE))); }
        void until(long time) { session.observe(time, List.of()); }
        void settled() { assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty()); }
    }
    @Test void sharedDirectAndSnapshotComponentsKeepEligibilityButFollowingIndependentHitDoesNot() throws Exception {
        for (String event : List.of("group", "group_snapshot")) {
            var h = new Harness(); h.run("arm"); h.run(event); h.settled();
            assertEquals(List.of(25.0, 25.0, 10.0), h.amounts); assertEquals(List.of(1, 0, 0), h.liveAtIssue);
            assertTrue(h.commands.get(1).consumptions().isEmpty()); assertTrue(h.state().damageGroups().isEmpty()); assertTrue(h.state().timers().isEmpty());
            assertNotEquals(DamageBatch.reference(h.commands.get(0), receipt("one")), DamageBatch.reference(h.commands.get(1), receipt("two")), "shared eligibility does not merge damage batches");
        }
    }
    @Test void policyIsOptInAndNeitherSameRootNorTwoDistinctGroupsShareTheFirstGroupsGrant() throws Exception {
        var h = new Harness(); h.run("arm"); h.run("independent"); assertEquals(List.of(25.0, 10.0, 25.0), h.amounts); h.settled();
        var data = json("damage_group"); data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("consume_on_damage").remove("sharing");
        var perDamage = new Harness(data); perDamage.run("arm"); perDamage.run("group"); assertEquals(List.of(25.0, 10.0, 10.0), perDamage.amounts);
        var ungrouped = new Harness(); ungrouped.run("arm"); ungrouped.run("double"); assertEquals(List.of(25.0, 10.0), ungrouped.amounts);
    }
    @Test void cancelledAndFailedComponentsCannotReserveOrRetainTheBuffAheadOfAnIndependentHit() throws Exception {
        for (var outcome : List.of(DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.FAILED)) {
            var h = new Harness(); h.first = outcome; h.run("arm"); h.run("release");
            assertEquals(List.of(25.0, 25.0, 10.0), h.amounts); h.settled();
        }
    }
    @Test void immuneOrBlockedHitsShareOnlyIfThePolicyConsumesOnHit() throws Exception {
        for (var outcome : List.of(DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.BLOCKED)) {
            var h = new Harness(); h.first = outcome; h.run("arm"); h.run("release"); assertEquals(List.of(25.0, 10.0, 25.0), h.amounts);
            var data = json("damage_group"); data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("consume_on_damage").addProperty("when", "effective_damage");
            var effective = new Harness(data); effective.first = outcome; effective.run("arm"); effective.run("release"); assertEquals(List.of(25.0, 25.0, 10.0), effective.amounts);
        }
    }
    @Test void newGenerationIsNeitherDoubleCountedNorConsumedByLaterMembersOfTheOldAttack() throws Exception {
        var h = new Harness(); h.run("arm"); h.run("reapply");
        assertEquals(List.of(25.0, 25.0, 25.0, 25.0), h.amounts); assertEquals(List.of(1, 1, 1, 0), h.liveAtIssue);
        assertTrue(h.commands.get(1).consumptions().isEmpty()); assertEquals(1, h.commands.get(2).consumptions().size());
        assertNotEquals(h.commands.get(0).consumptions().getFirst().generation(), h.commands.get(2).consumptions().getFirst().generation());
    }
    @Test void sharedAttackConsumesOneOfSeveralChargesWhileEachMemberReadsTheOriginalStackCount() throws Exception {
        var data = json("damage_group");
        data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("max_stacks", 3);
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject()
                .add("stacks", JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":3,\"unit\":\"count\"}"));
        data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject()
                .add("value", JsonParser.parseString("{\"type\":\"chorus:by_stacks\",\"values\":[0.5,1,1.5],\"unit\":\"delta\"}"));
        var h = new Harness(data); h.run("arm"); h.run("group");
        assertEquals(List.of(25.0, 25.0, 20.0), h.amounts);
        assertEquals(1, h.state().buffs().instances().values().iterator().next().count());
    }
    @Test void retainedOwnComponentsRemainReadableWhileEachComponentStillNeedsItsDeclaredTags() throws Exception {
        var data = json("damage_group");
        var definition = data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition");
        definition.add("components", JsonParser.parseString("{\"numbers\":{\"bonus\":{\"initial\":1.5,\"unit\":\"delta\"}}}"));
        data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().add("value", JsonParser.parseString("{\"type\":\"chorus:component\",\"buff\":\"test:next_hit\",\"component\":\"bonus\"}"));
        var h = new Harness(data); h.run("arm"); h.run("nonmember"); assertEquals(List.of(25.0, 10.0, 25.0), h.amounts); h.settled();
    }
    @Test void targetBuffConditionsAreReevaluatedWithoutRestoringTheConsumedSourceBuff() throws Exception {
        var data = json("damage_group");
        data.getAsJsonArray("buffs").add(JsonParser.parseString("{\"definition\":{\"id\":\"test:weak\",\"version\":\"test-1\",\"duration\":3}}"));
        var modifier = data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject();
        modifier.add("value", JsonParser.parseString("{\"type\":\"chorus:choose\",\"if\":{\"type\":\"chorus:has_buff\",\"buff\":\"test:weak\",\"target\":\"victim\"},\"then\":{\"type\":\"chorus:constant\",\"value\":2.5,\"unit\":\"delta\"},\"else\":{\"type\":\"chorus:constant\",\"value\":1.5,\"unit\":\"delta\"}}"));
        var steps = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(3).getAsJsonObject().getAsJsonArray("do");
        var altered = new JsonArray(); altered.add(steps.get(0)); altered.add(steps.get(1));
        altered.add(JsonParser.parseString("{\"type\":\"chorus:grant_buff\",\"buff\":\"test:weak\",\"target\":\"victim\"}")); altered.add(steps.get(2)); altered.add(steps.get(3));
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(3).getAsJsonObject().add("do", altered);
        var h = new Harness(data); h.run("arm"); h.run("group"); assertEquals(List.of(25.0, 35.0), h.amounts);
        assertFalse(h.state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("test:next_hit")));
    }
    @Test void detachedLaterComponentsKeepOnlyExplicitGroupEligibilityAndExpiryIsHalfOpen() throws Exception {
        var h = new Harness(); h.run("arm"); h.run("delayed"); assertEquals(List.of(25.0), h.amounts);
        h.until(200_000); assertEquals(List.of(25.0, 25.0), h.amounts); assertTrue(h.state().damageGroups().isEmpty()); h.settled();
        var expiry = new Harness(); expiry.run("arm"); expiry.run("expiry"); var handle = expiry.commands.getFirst().group().orElseThrow();
        var before = expiry.state(); expiry.until(999_999); assertEquals(1, expiry.state().damageGroups().size());
        expiry.until(1_000_000); assertTrue(expiry.state().damageGroups().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> expiry.program.prepareDamage(expiry.state(), expiry.commands.getFirst()));
        assertThrows(IllegalArgumentException.class, () -> DamageGroups.require(expiry.state(), handle)); assertEquals(1, before.damageGroups().size());
    }
    @Test void explicitCloseRejectsFurtherDamageWithoutReplayingPriorComponents() throws Exception {
        var h = new Harness(); h.run("arm"); assertThrows(IllegalStateException.class, () -> h.run("closed"));
        assertEquals(List.of(25.0), h.amounts); assertTrue(h.state().damageGroups().isEmpty());
    }
    @Test void unknownFirstOrLaterWorldOutcomePreservesExactlyTheAlreadyConfirmedConsumption() throws Exception {
        for (int index : List.of(1, 2)) {
            var h = new Harness(); h.unknownAt = index; h.run("arm"); assertThrows(IllegalStateException.class, () -> h.run("group"));
            assertEquals(index == 1 ? 1 : 0, h.state().buffs().instances().size());
            assertEquals(index == 1 ? 0 : 1, h.state().damageGroups().values().iterator().next().grants().size());
            assertTrue(h.session.state().engine().pending().isPresent()); assertThrows(IllegalStateException.class, () -> h.run("group")); assertEquals(index, h.commands.size());
        }
    }
    @Test void groupMembershipCannotCrossOwnersOrBeFrozenInsideAReusableDamageSnapshot() throws Exception {
        var h = new Harness(); h.run("arm"); h.run("expiry"); var c = h.commands.getFirst(); var handle = c.group().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> c.withGroup(new DamageGroups.Handle("other", "other", 0, 10)));
        assertThrows(IllegalArgumentException.class, () -> h.program.captureDamage(h.state(), c));
        assertThrows(IllegalArgumentException.class, () -> DamageGroups.require(h.state(), new DamageGroups.Handle(handle.id(), "player", 0, handle.dueAt() + 1)));
        var state = h.state(); h.program.outgoing(state, c, 10); h.program.outgoing(state, c, 10); assertEquals(state, h.state());
    }
    @Test void codecRoundTripAndLexicalTypeValidationRejectBatchHandlesAsAttackGroups() throws Exception {
        var data = json("damage_group"); var p = compile(data).program();
        assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        var first = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(3).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action");
        first.addProperty("type", "chorus:begin_damage_batch"); first.remove("lifetime"); assertThrows(RuntimeException.class, () -> compile(data));
    }
    @Test void retainedEligibilityAlsoReachesCurrentAndCapturedPerLayerAttackProfiles() throws Exception {
        var data = json("damage_group");
        data.getAsJsonArray("profiles").add(JsonParser.parseString("{\"id\":\"test:shield_attack\",\"version\":\"test-1\",\"input_unit\":\"multiplier\",\"steps\":[{\"type\":\"chorus:apply\",\"id\":\"bonus\",\"operation\":\"multiply\",\"group\":{\"name\":\"bonus\",\"reduction\":\"sum\"}}]}"));
        data.getAsJsonArray("buffs").add(json("shield_scaling").getAsJsonArray("buffs").get(0));
        var rules = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules");
        rules.get(0).getAsJsonObject().getAsJsonArray("do").add(JsonParser.parseString("{\"type\":\"chorus:grant_buff\",\"buff\":\"test:elemental\",\"target\":\"victim\"}"));
        var modifiers = data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers");
        var extra = modifiers.get(0).getAsJsonObject().deepCopy(); extra.addProperty("id", "shield"); extra.addProperty("profile", "test:shield_attack"); extra.getAsJsonObject("value").addProperty("value", 1); modifiers.add(extra);
        var h = new Harness(data); h.run("arm"); h.run("expiry"); var group = h.commands.getFirst().group().orElseThrow();
        var attack = new DamageCommand("target", SOURCE.origin(), 10, "minecraft:generic", Set.of("test:melee"), Set.of(), false,
                Optional.of("test:melee"), Optional.empty(), ImpactData.EMPTY, Optional.of("test:shield_attack"));
        var snapshot = h.program.captureDamage(h.state(), attack);
        for (var command : List.of(attack.withGroup(group), snapshot.command("target", ImpactData.EMPTY).withGroup(group)))
            assertEquals(2, h.program.shields(h.state(), command, 10).layers().getFirst().attackScaling().orElseThrow().output().value());
        assertEquals(1, h.program.shields(h.state(), attack, 10).layers().getFirst().attackScaling().orElseThrow().output().value());
    }
    @Test void factsExposeAuthoritativeGroupMembershipWithoutMergingReceiptsOrPropagatingEligibility() throws Exception {
        var h = new Harness(); h.run("arm"); h.run("group");
        var first = (EffectEvent) DamageFacts.from(h.commands.getFirst(), receipt("first"), Map.of("attack_group", "forged")).getFirst().payload();
        var second = (EffectEvent) DamageFacts.from(h.commands.get(1), receipt("second")).getFirst().payload();
        assertEquals(h.commands.getFirst().group().orElseThrow().reference(), first.references().get("attack_group"));
        assertEquals(first.references().get("attack_group"), second.references().get("attack_group"));
        assertNotEquals(first.references().get("damage_id"), second.references().get("damage_id"));
        var independent = (EffectEvent) DamageFacts.from(h.commands.getLast(), receipt("third"), first.references()).getFirst().payload();
        assertFalse(independent.references().containsKey("attack_group"));
    }
    private static DamageReceipt receipt(String id) { return new DamageReceipt(id, DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.empty(), false); }
}
