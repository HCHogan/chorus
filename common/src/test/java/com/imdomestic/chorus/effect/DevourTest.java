package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DevourTest {
    static final String BUFF = "chorus_d2:devour", SLOT = "chorus_d2:grenade";
    static EffectSource application(String id, String giver, double duration, double cap) {
        return new EffectSource(id, BUFF + "_application", giver, new BuffInstance.Origin(giver, id, "", ""), Set.of(), Map.of("duration", new Measure(duration, Unit.SECOND), "cap", new Measure(cap, Unit.SECOND), "receipt_seconds", new Measure(1, Unit.SECOND)));
    }
    static class Harness {
        final CompiledEffects p; EffectSession session;
        final Map<String, Double> health = new HashMap<>(); final Map<String, EntityQuery.View> kinds = new HashMap<>();
        final List<HealingCommand> heals = new ArrayList<>(); final List<String> cues = new ArrayList<>();
        boolean omitEntities, unknownHeal, denyHeal; DamageCommand lastCommand; DamageReceipt lastReceipt; Runnable afterDamage = () -> {};
        Harness(String... additional) throws Exception {
            var modules = new ArrayList<>(List.of("devour", "devour_inputs")); modules.addAll(List.of(additional));
            p = link(modules.toArray(String[]::new)); entity("player", 100, true, Set.of()); entity("giver", 100, true, Set.of()); classify(1, false);
            session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty(), this::world);
            bind("input", "test:devour_inputs", "player"); bind("other_input", "test:devour_inputs", "giver"); select("a");
        }
        void entity(String id, double hp, boolean player, Set<String> tags) { health.put(id, hp); kinds.put(id, new EntityQuery.View(hp > 0, player, hp, 1000, 0, tags, Set.of())); }
        void classify(int tier, boolean type) { entity("enemy", 1, tier == 7, type ? Set.of() : Set.of("chorus_d2:combatant_tier_" + tier)); if (type) kinds.put("enemy", new EntityQuery.View(true, false, 1, 1000, 0, Set.of(), Set.of("chorus_d2:combatant_tier_" + tier))); }
        Optional<EntityQuery.View> view(String id) { return Optional.ofNullable(kinds.get(id)).map(v -> new EntityQuery.View(health.get(id) > 0, v.player(), health.get(id), 1000, 0, v.entityTags(), v.typeTags())); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) { return switch (r.command()) {
            case EntityQuery q -> new EntityQuery.Result(q, view(q.target()));
            case HealingCommand h -> {
                heals.add(h); double loss = denyHeal ? 0 : Math.min(h.amount(), 1000 - health.get(h.target())); health.compute(h.target(), (_, hp) -> hp + loss);
                if (unknownHeal) throw new IllegalStateException("unknown Devour healing after health mutation");
                yield denyHeal ? HealingReceipt.unapplied(r.id().toString(), h, HealingReceipt.Outcome.REJECTED) : new HealingReceipt(r.id().toString(), h, HealingReceipt.Outcome.APPLIED, h.amount(), loss, h.amount() - loss);
            }
            case DamageCommand d -> {
                var buffs = BuffObservation.capture(state().buffs(), d.source().owner(), d.target()); double before = health.get(d.target()), loss = Math.min(before, d.amount()); health.put(d.target(), before - loss);
                var receipt = new DamageReceipt(r.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, loss, before > 0 && loss == before ? Optional.of("death/" + r.id()) : Optional.empty(), false).withObservedBuffs(buffs);
                if (!omitEntities) receipt = receipt.withObservedEntities(new EntityObservation(now(), Map.of(d.target(), view(d.target()))));
                lastCommand = d; lastReceipt = receipt; afterDamage.run(); yield receipt;
            }
            case Action.CueCommand cue -> { cues.add(cue.cue()); yield RuleEngine.Empty.INSTANCE; }
            default -> throw new AssertionError(r.command());
        }; }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void healthy() { assertTrue(session.state().idle() && session.state().engine().failure().isEmpty(), session.state().engine().failure().toString()); }
        void until(long time) { session.observe(time, List.of()); healthy(); }
        void bind(String id, String bundle, String owner) { session.start(now(), SourceChange.bind(new EffectSource(id, bundle, owner, new BuffInstance.Origin(owner, id, "", ""), Set.of()))); healthy(); }
        void remove(String id) { session.start(now(), SourceChange.remove(id)); healthy(); }
        void select(String name) { session.start(now(), new AbilityChange("player", state().abilities().getOrDefault("player", AbilityLoadout.EMPTY), name == null ? AbilityLoadout.EMPTY : new AbilityLoadout(Map.of(SLOT, "test:grenade_" + name))).signal()); healthy(); }
        void event(EffectSource source, String target, String kind) { session.start(now(), new RuleEngine.Signal("chorus_d2:" + kind, new EffectEvent(source.holder(), target, source.origin(), Set.of(), Map.of(), Map.of(), Map.of("source_instance", source.instance(), "bundle", source.bundle())))); healthy(); }
        void grant(EffectSource source, String target) { session.start(now(), SourceChange.bind(source)); event(source, target, "grant_devour"); }
        void grant() { grant(application("grant", "player", 10, 10), "player"); }
        void damage(String owner, String target, double value) { session.start(now(), new RuleEngine.Signal("test:damage", new EffectEvent(owner, target, new BuffInstance.Origin(owner, "input", "weapon", ""), Set.of(), Map.of("amount", new Measure(value, Unit.DAMAGE))))); healthy(); }
        void kill() { health.put("enemy", 1.0); damage("player", "enemy", 10); }
        double energy(String name) { return state().resources().get(new ResourceState.Key("player", "test:grenade_" + name)).value(); }
        BuffInstance buff() { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(BUFF) && b.key().holder().equals("player")).findFirst().orElseThrow(); }
        boolean active() { return state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals(BUFF)); }
        long remaining() { return buff().deadline() - now(); }
        List<RuleEngine.Signal> facts() { return DamageFacts.from(lastCommand, lastReceipt); }
        void replay(List<RuleEngine.Signal> signals) { session.observe(now(), signals); healthy(); }
        void restore() { var checkpoint = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())); session = EffectSession.restore(new CompiledEffects(checkpoint.program()), EffectCheckpointTest.clock(), 256, checkpoint, this::world); }
    }
    @Test void activationAndReactivationHealOnceButExtendRemainingTimeInsteadOfResettingIt() throws Exception {
        var h = new Harness(); h.grant(); assertEquals(170, h.health.get("player")); assertEquals(10_000_000, h.remaining()); assertEquals(0, h.energy("a")); var first = h.buff();
        h.until(8_000_000); h.grant(application("second", "giver", 10, 10), "player"); assertEquals(240, h.health.get("player")); assertEquals(7_000_000, h.remaining()); assertEquals(first.origin(), h.buff().origin()); assertEquals(first.generation(), h.buff().generation());
        h.grant(); assertEquals(10_000_000, h.remaining()); assertEquals(310, h.health.get("player")); assertEquals(0, h.energy("a"));
    }
    @Test void everyTierGuardianAndEnhancementUseTheQuotedTableThenRecipientScalingExactlyOnce() throws Exception {
        double[] base = {.075, .116, .1375, .2, .2, .2, .2};
        for (int tier = 1; tier <= 7; tier++) for (boolean enhanced : List.of(false, true)) {
            var h = new Harness(); h.classify(tier, tier < 7 && tier % 2 == 0); if (enhanced) h.bind("enhance", "chorus_d2:enhanced_devour", "player"); h.bind("stat", "test:devour_stat_gain", "player"); h.grant(); h.kill();
            assertEquals(base[tier - 1] * (enhanced ? 2 : 1) * .5 * 2.25, h.energy("a"), 1e-12); assertEquals(enhanced ? 380 : 240, h.health.get("player")); assertTrue(h.cues.isEmpty());
        }
    }
    @Test void quotedPersistenceExtensionIsEightSecondsAndExplicitCapsSupportLongerGrantors() throws Exception {
        var h = new Harness(); h.bind("persistence", "test:devour_persistence_extension", "player"); h.grant(application("grant", "player", 15, 15), "player"); h.until(14_000_000); h.kill(); assertEquals(9_000_000, h.remaining()); h.kill(); assertEquals(15_000_000, h.remaining());
        h.grant(application("long", "giver", 20, 20), "player"); assertEquals(20_000_000, h.remaining()); h.grant(application("short", "giver", 5, 10), "player"); assertEquals(20_000_000, h.remaining(), "later short provider cannot lower an already admitted cap");
    }
    @Test void recipientOwnsTheKillBenefitsEvenWhenAnotherActorOriginallyGrantedDevour() throws Exception {
        var h = new Harness(); h.grant(application("ally", "giver", 10, 10), "player"); h.damage("giver", "enemy", 10); assertEquals(170, h.health.get("player")); assertEquals(0, h.energy("a"));
        h.remove("ally"); h.kill(); assertEquals(240, h.health.get("player")); assertEquals(.0375, h.energy("a"), 1e-12); assertEquals(100, h.health.get("giver"));
    }
    @Test void missingOrConflictingClassificationOnlySkipsEnergyAndHistoricalTierSurvivesCorpseRemoval() throws Exception {
        for (String bad : List.of("missing", "conflict", "observation")) {
            var h = new Harness(); h.grant(); h.until(8_000_000); h.entity("enemy", 1, false, bad.equals("conflict") ? Set.of("chorus_d2:combatant_tier_1", "chorus_d2:combatant_tier_4") : Set.of()); h.omitEntities = bad.equals("observation"); h.kill();
            assertEquals(240, h.health.get("player")); assertEquals(7_000_000, h.remaining()); assertEquals(0, h.energy("a")); assertEquals(List.of("test:devour_unclassified"), h.cues);
        }
        var h = new Harness(); h.grant(); h.classify(2, true); h.afterDamage = () -> h.kinds.remove("enemy"); h.kill(); assertEquals(.116 * .5, h.energy("a"), 1e-12);
    }
    @Test void currentGrenadeSelectionRoutesEnergyAndAbsentSelectionDoesNotCancelOtherBenefits() throws Exception {
        var h = new Harness(); h.grant(); h.kill(); h.select("b"); h.kill(); assertEquals(.075 * .5, h.energy("a"), 1e-12); assertEquals(.075 * .75, h.energy("b"), 1e-12);
        h.select(null); h.kill(); assertEquals(380, h.health.get("player")); assertEquals(.075 * .75, h.energy("b"), 1e-12);
    }
    @Test void duplicateFactsDoNotRepeatRewardsButIndependentKillsHaveNoInventedCooldown() throws Exception {
        var h = new Harness(); h.grant(); h.until(8_000_000); h.kill(); var facts = h.facts(); h.until(8_200_000); h.replay(facts); assertEquals(240, h.health.get("player")); assertEquals(15_000_000, h.buff().deadline()); assertEquals(.0375, h.energy("a"), 1e-12);
        for (int i = 0; i < 12; i++) h.kill(); assertEquals(.0375 * 13, h.energy("a"), 1e-12); h.until(9_300_000); assertTrue(h.buff().components().sets().get("death_ids").isEmpty()); assertTrue(h.active());
    }
    @Test void priorKillCannotGainBenefitsRetroactivelyAfterNewDevourAndNonlethalHitsDoNotProc() throws Exception {
        var h = new Harness(); h.kill(); var beforeGrant = h.facts(); h.grant(); h.replay(beforeGrant); assertEquals(170, h.health.get("player")); assertEquals(0, h.energy("a")); h.entity("enemy", 100, false, Set.of("chorus_d2:combatant_tier_1")); h.damage("player", "enemy", 10); assertEquals(170, h.health.get("player"));
    }
    @Test void enhancementsAreCurrentRecipientModifiersAndDuplicateSourcesDoNotMultiplyTwice() throws Exception {
        var h = new Harness(); h.grant(); h.bind("enhance", "chorus_d2:enhanced_devour", "player"); h.bind("duplicate", "chorus_d2:enhanced_devour", "player"); h.kill(); assertEquals(310, h.health.get("player")); assertEquals(.075, h.energy("a"), 1e-12);
        h.remove("enhance"); h.remove("duplicate"); h.kill(); assertEquals(380, h.health.get("player")); assertEquals(.1125, h.energy("a"), 1e-12);
    }
    @Test void invalidMissingAndDeadRecipientsDoNotActivateWhileHealClippingDoesNotBlockOtherBenefits() throws Exception {
        for (String reason : List.of("invalid", "missing", "dead")) {
            var h = new Harness(); if (reason.equals("missing")) h.kinds.remove("player"); if (reason.equals("dead")) h.health.put("player", 0.0); h.grant(application("grant", "player", 10, reason.equals("invalid") ? 5 : 10), "player"); assertFalse(h.active()); assertTrue(h.heals.isEmpty());
        }
        var h = new Harness(); h.health.put("player", 990.0); h.grant(); assertEquals(1000, h.health.get("player")); h.denyHeal = true; h.until(8_000_000); h.kill(); assertEquals(1000, h.health.get("player")); assertEquals(.0375, h.energy("a"), 1e-12); assertEquals(7_000_000, h.remaining());
    }
    @Test void expiryCleanseDeathAndCheckpointRespectLifecycleAndReceiptHistory() throws Exception {
        for (String reason : List.of("expiry", "cleanse", "death")) {
            var h = new Harness(); var source = application("grant", "player", 10, 10); h.grant(source, "player"); h.kill(); var facts = h.facts(); h.restore(); h.replay(facts); assertEquals(240, h.health.get("player"));
            if (reason.equals("expiry")) h.until(10_000_000); else if (reason.equals("cleanse")) h.event(source, "player", "clear_devour"); else h.damage("giver", "player", 2000);
            assertFalse(h.active());
        }
    }
    @Test void unknownHealingKeepsCommittedHealthAndPriorDurationButNeverInventsEnergyOrRetries() throws Exception {
        var h = new Harness(); h.grant(); h.until(8_000_000); h.unknownHeal = true; assertThrows(IllegalStateException.class, h::kill); assertEquals(240, h.health.get("player")); assertEquals(7_000_000, h.remaining()); assertEquals(0, h.energy("a")); assertThrows(IllegalStateException.class, () -> h.until(8_100_000)); assertEquals(2, h.heals.size());
        var roundtrip = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.p.program()).getOrThrow(); assertEquals(h.p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, roundtrip).getOrThrow());
    }
}
