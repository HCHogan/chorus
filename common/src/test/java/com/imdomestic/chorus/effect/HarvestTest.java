package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class HarvestTest {
    static final String D = "chorus_d2:";
    static EffectSource fragment(String id) { return new EffectSource(id, D + "echo_of_harvest", "player", new BuffInstance.Origin("player", id, "", ""), Set.of(), Map.of("orb_lifetime", new Measure(20, Unit.SECOND), "orb_collection_radius", new Measure(.5, Unit.METER), "receipt_seconds", new Measure(12, Unit.SECOND))); }
    static class Harness extends StarvationTest.Harness {
        final Map<String, WorldPickup.Outcome> outcomes = new HashMap<>();
        boolean omitBuffs, omitPosition, missingPosition;
        Harness() throws Exception { super("echo_of_harvest", "weaken", "strand_defense", "combat_damage", "movement_attributes"); equip(fragment("harvest")); }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            if (r.command() instanceof StatusResult.Check q) return new StatusResult.Checked(q, StatusResult.Decision.ALLOWED);
            if (r.command() instanceof DamageCommand d) {
                var buffs = omitBuffs ? new BuffObservation(now(), Map.of()) : BuffObservation.capture(state().buffs(), d.source().owner(), d.target());
                double before = health.get(d.target()), loss = Math.min(before, d.amount()); health.put(d.target(), before - loss);
                var entities = new EntityObservation(now(), Map.of(d.target(), view(d.target())), omitPosition ? Map.of() : Map.of(new PositionQuery(d.target()), missingPosition ? Optional.empty() : Optional.of(StarvationTest.POINT)));
                lastCommand = d; lastReceipt = new DamageReceipt(r.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, loss, before > 0 && loss == before ? Optional.of("death/" + r.id()) : Optional.empty(), false).withObservedBuffs(buffs).withObservedEntities(entities);
                afterDamage.run(); return lastReceipt;
            }
            if (r.command() instanceof WorldPickup.Spawn s) spawnOutcome = outcomes.getOrDefault(s.kind(), WorldPickup.Outcome.SPAWNED);
            return super.world(r);
        }
        void weaken() { health.put("enemy", 100.0); var s = WeakenTest.source("weaken", "giver", 6, 2.5, .15, .075); equip(s); event(s, "enemy", "apply_weaken"); }
        void harvest() { weaken(); kill(); }
        Optional<BuffInstance> status(String id) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + id) && b.key().holder().equals("player")).findFirst(); }
        long count(String kind) { return spawns.stream().filter(p -> p.kind().equals(D + kind)).count(); }
    }
    @Test void nonprecisionKillOfAnAllysWeakenedEnemyCreatesBothPickupsAtHistoricalDeathPosition() throws Exception {
        var h = new Harness(); h.afterDamage = () -> h.kinds.remove("enemy"); h.harvest();
        assertEquals(List.of(D + "orb_of_power", D + "void_breach"), h.spawns.stream().map(WorldPickup.Spawn::kind).toList());
        for (var s : h.spawns) { assertEquals(Optional.of(StarvationTest.POINT), s.position()); assertEquals("player", s.recipient()); assertEquals(h.lastCommand.source(), s.origin()); }
        assertTrue(h.state().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals(D + "weaken")));
        assertEquals(10_000_000, h.status("harvest_cooldown").orElseThrow().deadline()); assertTrue(h.status("harvest_pending").isEmpty());
        assertEquals(50, h.classStat(60), "only Starvation has a current negative stat modifier");
    }
    @Test void collectingBothRealContinuationsGrantsSuperClassEnergyAndDevourThenKillBenefits() throws Exception {
        var h = new Harness(); h.harvest(); h.finish(0, true); h.finish(1, true);
        assertEquals(.025, h.account("super"), 1e-12); assertEquals(.025, h.account("class_a"), 1e-12); assertEquals(240, h.health.get("player")); assertEquals(10_000_000, h.remaining()); assertEquals(0, h.energy("a"));
        h.kill(); assertEquals(.0375, h.energy("a"), 1e-12); assertEquals(310, h.health.get("player")); assertEquals(2, h.spawns.size());
    }
    @Test void noWeakenExpiredWeakenClearedWeakenUnknownBuffsOtherActorAndNonlethalDamageDoNotQualify() throws Exception {
        for (String reason : List.of("none", "expired", "cleared", "unknown", "other", "nonlethal")) {
            var h = new Harness(); if (!reason.equals("none")) h.weaken();
            if (reason.equals("expired")) h.until(6_000_000);
            if (reason.equals("cleared")) h.event(WeakenTest.source("weaken", "giver", 6, 2.5, .15, .075), "enemy", "clear_weaken");
            h.omitBuffs = reason.equals("unknown"); h.damage(reason.equals("other") ? "giver" : "player", "enemy", reason.equals("nonlethal") ? 1 : 1000);
            assertTrue(h.spawns.isEmpty(), reason); assertTrue(h.status("harvest_cooldown").isEmpty(), reason);
        }
    }
    @Test void tenSecondCooldownIsNotResetByCollectionOrUnbindAndOldCooldownPeriodKillsCannotReplay() throws Exception {
        var h = new Harness(); h.harvest(); h.finish(0, true); h.finish(1, true); h.until(5_000_000); h.harvest(); var suppressed = h.facts();
        h.remove("harvest"); h.restore(); h.equip(fragment("again")); h.until(9_999_999); h.harvest(); assertEquals(2, h.spawns.size());
        h.until(10_000_000); h.replay(suppressed); assertEquals(2, h.spawns.size(), "a past cooldown kill cannot become a new activation"); h.harvest(); assertEquals(4, h.spawns.size());
    }
    @Test void anAlreadyActiveBreachCooldownDoesNotBlockOrResetHarvestOrbAndItsTenSecondCooldown() throws Exception {
        var h = new Harness(); h.spawn("void_breach", "giver"); h.harvest(); assertEquals(1, h.count("orb_of_power")); assertEquals(1, h.count("void_breach"));
        h.until(5_000_000); h.spawn("void_breach", "giver"); assertEquals(2, h.count("void_breach")); h.harvest(); assertEquals(1, h.count("orb_of_power"));
    }
    @Test void knownRejectedOrbDoesNotStartHarvestCooldownButBreachMayStillSpawnAndNextKillMayRetry() throws Exception {
        var h = new Harness(); h.outcomes.put(D + "orb_of_power", WorldPickup.Outcome.UNLOADED); h.harvest();
        assertTrue(h.status("harvest_cooldown").isEmpty()); assertTrue(h.status("harvest_pending").isEmpty()); assertEquals(1, h.count("void_breach"));
        h.outcomes.clear(); h.harvest(); assertEquals(2, h.count("orb_of_power")); assertEquals(1, h.count("void_breach")); assertTrue(h.status("harvest_cooldown").isPresent());
    }
    @Test void knownRejectedBreachDoesNotUndoSuccessfulOrbOrItsCooldown() throws Exception {
        var h = new Harness(); h.outcomes.put(D + "void_breach", WorldPickup.Outcome.UNLOADED); h.harvest(); assertTrue(h.status("harvest_cooldown").isPresent()); assertTrue(h.status("void_breach_cooldown").isEmpty());
        h.outcomes.clear(); h.spawn("void_breach", "player"); assertEquals(2, h.count("void_breach")); h.harvest(); assertEquals(1, h.count("orb_of_power"));
    }
    @Test void missingOrUnobservedDeathPositionReleasesReservationWithoutInventingCooldown() throws Exception {
        for (boolean observed : List.of(false, true)) { var h = new Harness(); h.omitPosition = !observed; h.missingPosition = observed; h.harvest(); assertTrue(h.status("harvest_pending").isEmpty()); assertTrue(h.status("harvest_cooldown").isEmpty()); assertEquals(observed ? 2 : 0, h.spawns.size());
            h.omitPosition = false; h.missingPosition = false; h.harvest(); assertTrue(h.status("harvest_cooldown").isPresent()); }
    }
    @Test void duplicateSourcesFactsAndCheckpointShareReceiptsAndCooldownWithoutDuplicateSpawns() throws Exception {
        var h = new Harness(); h.equip(fragment("duplicate")); h.harvest(); var facts = h.facts(); h.restore(); h.replay(facts); assertEquals(2, h.spawns.size());
        h.until(10_000_000); h.replay(facts); assertEquals(2, h.spawns.size()); h.harvest(); assertEquals(4, h.spawns.size());
        h.until(22_000_000); assertTrue(h.status("harvest_receipts").isEmpty(), "bounded receipt retention");
    }
    @Test void queuedKillsReserveOneAttemptAndAcceptedWorkSurvivesFragmentDetachBeforeSpawn() throws Exception {
        var h = new Harness(); h.remove("harvest"); h.harvest(); var first = h.facts(); h.harvest(); var second = h.facts(); h.equip(fragment("harvest"));
        var queued = new ArrayList<>(first); queued.addAll(second); queued.add(SourceChange.remove("harvest")); h.replay(queued);
        assertEquals(2, h.spawns.size()); assertTrue(h.status("harvest_cooldown").isPresent()); assertTrue(h.status("harvest_pending").isEmpty());
    }
    @Test void removingHarvestAfterSpawnLeavesBothPickupsAndTheirStarvationBenefits() throws Exception {
        var h = new Harness(); h.harvest(); h.remove("harvest"); h.remove("weaken"); h.restore(); h.finish(0, true); h.finish(1, true); assertEquals(240, h.health.get("player")); assertEquals(.025, h.account("super"), 1e-12); assertEquals(.025, h.account("class_a"), 1e-12);
    }
    @Test void unknownOrbCreationKeepsReservationAndStopsWithoutInventingBreachOrRetry() throws Exception {
        var h = new Harness(); h.weaken(); h.failSpawn = true; assertThrows(IllegalStateException.class, h::kill); assertEquals(1, h.spawns.size()); assertTrue(h.status("harvest_pending").isPresent()); assertTrue(h.status("harvest_cooldown").isEmpty());
        assertThrows(IllegalStateException.class, () -> h.until(10_000_000)); assertEquals(1, h.spawns.size());
    }
}
