package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StarvationTest {
    static final String D = "chorus_d2:";
    static final WorldPosition POINT = new WorldPosition("test:world", 4, 40, 3);
    static EffectSource fragment(String id, double cap) { return new EffectSource(id, D + "echo_of_starvation", "player", new BuffInstance.Origin("player", id, "", ""), Set.of(), Map.of("cap", new Measure(cap, Unit.SECOND), "receipt_seconds", new Measure(1, Unit.SECOND))); }
    static EffectSource producer(String kind, String creator) { return new EffectSource("producer/" + kind, D + (kind.equals("orb_of_power") ? "orb_of_power_application" : "void_breach_system"), "player", new BuffInstance.Origin(creator, "producer", "", ""), Set.of(), kind.equals("orb_of_power") ? Map.of("super_energy", new Measure(.025, Unit.CHARGE), "lifetime", new Measure(20, Unit.SECOND), "collection_radius", new Measure(.5, Unit.METER)) : Map.of()); }
    static final class Harness extends DevourTest.Harness {
        final List<WorldPickup.Spawn> spawns = new ArrayList<>(); WorldPickup.Outcome spawnOutcome = WorldPickup.Outcome.SPAWNED; boolean failSpawn;
        Harness() throws Exception {
            super("echo_of_starvation", "void_breach", "orb_of_power", "starvation_inputs", "character_stats"); bind("starvation_inputs", "test:starvation_inputs", "player"); selectMore("class", "class_a"); selectMore("super", "super"); equip(fragment("starvation", 10));
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            if (r.command() instanceof WorldPickup.Spawn spawn) { spawns.add(spawn); if (failSpawn) throw new IllegalStateException("unknown pickup creation"); var outcome = spawn.position().isEmpty() ? WorldPickup.Outcome.MISSING_POSITION : spawnOutcome; return new WorldPickup.Receipt(spawn, outcome, outcome == WorldPickup.Outcome.SPAWNED ? Optional.of("pickup/" + spawns.size()) : Optional.empty()); }
            return super.world(r);
        }
        void equip(EffectSource source) { session.start(now(), SourceChange.bind(source)); healthy(); }
        void selectMore(String slot, String ability) { var before = state().abilities().getOrDefault("player", AbilityLoadout.EMPTY); var slots = new HashMap<>(before.slots()); if (ability == null) slots.remove(D + slot); else slots.put(D + slot, "test:" + ability); session.start(now(), new AbilityChange("player", before, new AbilityLoadout(slots)).signal()); healthy(); }
        double account(String name) { return state().resources().get(new ResourceState.Key("player", "test:" + name)).value(); }
        void fill(String name) { session.start(now(), new RuleEngine.Signal("test:fill_" + name, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of()))); healthy(); }
        void spawn(String kind, String creator) { spawn(kind, creator, Optional.of(new EntityObservation(now(), Map.of(), Map.of(new PositionQuery("enemy"), Optional.of(POINT))))); }
        void spawn(String kind, String creator, Optional<EntityObservation> observation) { var s = producer(kind, creator); equip(s); var e = new EffectEvent("player", "enemy", s.origin(), Set.of(), Map.of(), Map.of(), Map.of("source_instance", s.instance(), "bundle", s.bundle())).withObservedEntities(observation); session.start(now(), new RuleEngine.Signal(D + "spawn_" + kind, e)); healthy(); }
        void finish(int index, boolean collected) { var s = spawns.get(index); session.start(now(), s.finish(new WorldPickup.Contact(collected ? WorldPickup.End.COLLECTED : WorldPickup.End.EXPIRED, POINT, collected ? Optional.of("player") : Optional.empty(), collected ? 1 : s.parameters().lifetimeMicros()))); healthy(); }
        RuleEngine.Signal fact(int index) { var s = spawns.get(index); return new WorldPickup.Contact(WorldPickup.End.COLLECTED, POINT, Optional.of("player"), 1).fact(s.continuation().id(), s.kind(), s.origin()); }
        boolean eligible(int index) { return p.canCollectPickup(state(), spawns.get(index)); }
        double classStat(double input) { return p.calculate(state(), "player", new EffectEvent("player", "player", new BuffInstance.Origin("player", "query", "", ""), Set.of(), Map.of()), D + "class_stat", new Measure(input, Unit.STAT_POINT), List.of()).output().value(); }
        void clear() { var s = DevourTest.application("clear", "player", 10, 10); equip(s); event(s, "player", "clear_devour"); }
    }
    @Test void bothPickupKindsGrantFiveSecondsAndUseCurrentEnhancedDevourWithoutGrantingKillEnergy() throws Exception {
        for (String kind : List.of("orb_of_power", "void_breach")) for (boolean enhanced : List.of(false, true)) { var h = new Harness(); if (enhanced) h.bind("enhance", D + "enhanced_devour", "player"); h.spawn(kind, "player"); h.finish(0, true); assertEquals(enhanced ? 240 : 170, h.health.get("player")); assertEquals(5_000_000, h.remaining()); assertEquals(0, h.energy("a")); }
    }
    @Test void basePickupRewardAndStarvationAreIndependentAndUseTheCollectorsCurrentAccounts() throws Exception {
        var h = new Harness(); h.spawn("void_breach", "player"); h.selectMore("class", "class_b"); h.remove("producer/void_breach"); h.finish(0, true); assertEquals(.05 * .75, h.account("class_b"), 1e-12); assertEquals(0, h.account("class_a")); assertEquals(170, h.health.get("player"));
        h.spawn("orb_of_power", "giver"); h.finish(1, true); assertEquals(.025, h.account("super"), 1e-12); assertEquals(240, h.health.get("player")); assertEquals(100, h.health.get("giver")); assertEquals(10_000_000, h.remaining());
    }
    @Test void aFullSuperRequiresCurrentPickupPermissionWhichCanBeEquippedAfterSpawnAndRemovedAgain() throws Exception {
        var h = new Harness(); h.remove("starvation"); h.fill("super"); h.spawn("orb_of_power", "giver"); assertFalse(h.eligible(0)); h.equip(fragment("later", 10)); assertTrue(h.eligible(0)); h.remove("later"); assertFalse(h.eligible(0)); h.equip(fragment("again", 10)); h.finish(0, true); assertEquals(1, h.account("super")); assertEquals(170, h.health.get("player"));
    }
    @Test void missingSuperAccountDoesNotMasqueradeAsEmptyButStarvationStillPermitsItsOwnReward() throws Exception {
        var h = new Harness(); h.selectMore("super", null); h.remove("starvation"); h.spawn("orb_of_power", "player"); assertFalse(h.eligible(0)); h.equip(fragment("again", 10)); assertTrue(h.eligible(0)); h.finish(0, true); assertEquals(170, h.health.get("player")); assertEquals(0, h.account("super"));
    }
    @Test void duplicateFactsAndDuplicateFragmentsDoNotDoubleGrantAfterCleanseRebindOrCheckpoint() throws Exception {
        var h = new Harness(); h.equip(fragment("duplicate", 10)); h.spawn("orb_of_power", "player"); h.finish(0, true); assertEquals(170, h.health.get("player")); assertEquals(1, h.heals.size()); h.clear(); h.remove("starvation"); h.remove("duplicate"); h.equip(fragment("again", 10)); h.restore(); h.replay(List.of(h.fact(0))); assertFalse(h.active()); assertEquals(170, h.health.get("player")); h.spawn("orb_of_power", "player"); h.finish(1, true); assertEquals(240, h.health.get("player"));
    }
    @Test void fragmentPenaltyAndQuotedPersistenceInitialDurationAreSeparateFromEightSecondExtension() throws Exception {
        var h = new Harness(); assertEquals(40, h.classStat(50)); assertEquals(0, h.classStat(5)); h.remove("starvation"); assertEquals(50, h.classStat(50)); h.equip(fragment("again", 15)); h.bind("persistence", "test:starvation_persistence", "player"); h.spawn("orb_of_power", "player"); h.finish(0, true); assertEquals(7_500_000, h.remaining()); h.until(6_500_000); h.spawn("orb_of_power", "player"); h.finish(1, true); assertEquals(9_000_000, h.remaining()); assertEquals(240, h.health.get("player"));
    }
    @Test void sharedBreachCooldownStartsOnlyOnSuccessfulCreationAndCollectionDoesNotResetIt() throws Exception {
        var h = new Harness(); h.spawnOutcome = WorldPickup.Outcome.UNLOADED; h.spawn("void_breach", "player"); h.spawnOutcome = WorldPickup.Outcome.SPAWNED; h.spawn("void_breach", "player"); h.spawn("void_breach", "player"); assertEquals(2, h.spawns.size()); h.finish(1, true); h.until(4_999_999); h.spawn("void_breach", "player"); assertEquals(2, h.spawns.size()); h.until(5_000_000); h.spawn("void_breach", "player"); assertEquals(3, h.spawns.size()); assertEquals(25_000_000, h.spawns.getLast().parameters().lifetimeMicros());
    }
    @Test void expiryUnknownPositionAndUnrelatedPickupNeverInventDevourOrBasicEnergy() throws Exception {
        var h = new Harness(); h.spawn("void_breach", "player", Optional.empty()); assertTrue(h.spawns.isEmpty()); h.spawn("void_breach", "player"); h.until(25_000_000); h.finish(0, false); assertFalse(h.active()); assertEquals(0, h.account("class_a"));
        h.replay(List.of(new WorldPickup.Contact(WorldPickup.End.COLLECTED, POINT, Optional.of("player"), 1).fact("other", D + "firesprite", new BuffInstance.Origin("player", "other", "", "")))); assertFalse(h.active());
    }
    @Test void fragmentRemovalLeavesOldPickupAndActiveDevourButDoesNotGrantNewDevourOnCollection() throws Exception {
        var h = new Harness(); h.spawn("orb_of_power", "player"); h.finish(0, true); h.spawn("orb_of_power", "player"); h.remove("starvation"); assertTrue(h.active()); assertTrue(h.eligible(1)); h.finish(1, true); assertEquals(.05, h.account("super"), 1e-12); assertEquals(170, h.health.get("player")); assertEquals(5_000_000, h.remaining());
    }
    @Test void fullClassEnergyStillCollectsBreachAndDeadRecipientsNeverGainDevour() throws Exception {
        var h = new Harness(); h.fill("class_a"); h.spawn("void_breach", "player"); assertTrue(h.eligible(0)); h.finish(0, true); assertEquals(1, h.account("class_a")); assertEquals(170, h.health.get("player"));
        h.clear(); h.health.put("player", 0.0); h.replay(List.of(new WorldPickup.Contact(WorldPickup.End.COLLECTED, POINT, Optional.of("player"), 1).fact("late", D + "orb_of_power", new BuffInstance.Origin("giver", "other", "", "")))); assertFalse(h.active()); assertEquals(1, h.heals.size());
    }
    @Test void independentRapidPickupsHaveNoRewardCooldownAndCanBeFollowedByRealKillBenefits() throws Exception {
        var h = new Harness(); for (int i = 0; i < 12; i++) { h.spawn("orb_of_power", "player"); h.finish(i, true); } assertEquals(12, h.heals.size()); assertEquals(.3, h.account("super"), 1e-12); assertEquals(0, h.energy("a")); h.kill(); assertEquals(.0375, h.energy("a"), 1e-12);
    }
    @Test void unknownHealingKeepsBasePickupEnergyAndCommittedHealthWithoutRetry() throws Exception {
        var h = new Harness(); h.spawn("orb_of_power", "giver"); h.unknownHeal = true; assertThrows(IllegalStateException.class, () -> h.finish(0, true)); assertEquals(.025, h.account("super")); assertEquals(170, h.health.get("player")); assertTrue(h.active()); assertThrows(IllegalStateException.class, () -> h.until(100_000)); assertEquals(1, h.heals.size());
    }
}
