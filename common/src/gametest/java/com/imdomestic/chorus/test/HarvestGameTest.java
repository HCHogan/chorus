package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

/** Shared Weaken -> actual death -> physical Orb/Breach -> Starvation/Devour. */
public class HarvestGameTest {
    static final String D = "chorus_d2:";
    static EffectSource fragment(ProjectileGameTest.Harness t, String id) { return new EffectSource(id, D + "echo_of_harvest", owner(t), new BuffInstance.Origin(owner(t), id, "", ""), Set.of(), Map.of("orb_lifetime", new Measure(20, Unit.SECOND), "orb_collection_radius", new Measure(.5, Unit.METER), "receipt_seconds", new Measure(12, Unit.SECOND))); }
    static ProjectileGameTest.Harness open(GameTestHelper h, boolean enhanced) throws Exception {
        var t = StarvationGameTest.open(h, enhanced, "echo_of_harvest", "weaken", "strand_defense", "movement_attributes");
        try { t.runtime.bind(fragment(t, "harvest")); return t; } catch (Exception | Error e) { t.close(); throw e; }
    }
    static EffectSource weaken(ProjectileGameTest.Harness t, LivingEntity giver, LivingEntity victim) {
        var s = WeakenGameTest.source(giver, "harvest_weaken", 6, 2.5, .15, .075); t.runtime.bind(s); FreezeGameTest.event(t.runtime, s, victim, "apply_weaken"); ShieldThrowGameTest.settled(t);
        t.h.assertTrue(state(t).buffs().instances().values().stream().anyMatch(b -> b.key().holder().equals(victim.getUUID().toString()) && b.definition().id().equals(D + "weaken")), "shared real Weaken applied"); return s;
    }
    static LivingEntity kill(ProjectileGameTest.Harness t, int index) { var victim = FeedTheVoidGameTest.target(t, index, 1); weaken(t, t.owner, victim); DevourGameTest.nativeKill(t, victim); return victim; }
    static Optional<BuffInstance> status(ProjectileGameTest.Harness t, String id) { return state(t).buffs().instances().values().stream().filter(b -> b.key().holder().equals(owner(t)) && b.definition().id().equals(D + id)).findFirst(); }
    @GameCase(environment = "chorus_gametest:harvest_paid")
    public void paidShieldKillsAnAllysWeakenedTargetAndBothPhysicalPickupsActivateDevour(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            var victim = FeedTheVoidGameTest.target(t, 0, 1); var giver = ThreadedSpikeGameTest.target(t, 6.5, 40, 3.5, 1000); var point = victim.position(); weaken(t, giver, victim);
            FeedTheVoidGameTest.cast(t, false); FeedTheVoidGameTest.contacts(t, 1); h.assertTrue(victim.isDeadOrDying(), "paid physical skill killed target"); h.assertValueEqual(t.pickups.size(), 2, "one Orb and one Breach");
            for (var p : t.pickups) near(h, p.position().distanceTo(point), 0, "historical corpse position");
            near(h, t.owner.getHealth(), 100, "Harvest alone does not heal"); StarvationGameTest.collect(t, t.pickups.getFirst()); StarvationGameTest.collect(t, t.pickups.getLast());
            near(h, t.owner.getHealth(), 240, "two real Starvation heals"); near(h, giver.getHealth(), 1000, "Weaken giver does not collect private rewards"); near(h, StarvationGameTest.account(t, "super"), .025, "Harvest Orb 2.5 percent"); near(h, StarvationGameTest.account(t, "class_a"), .025, "calibrated class reward");
            near(h, DevourGameTest.energy(t, "a"), 0, "pickup has no kill grenade reward"); DevourGameTest.nativeKill(t, DevourGameTest.target(t, 1)); near(h, DevourGameTest.energy(t, "a"), .0375, "later native kill uses real Devour");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:harvest_history")
    public void DeathReceiptKeepsWeakenAndLocationAfterCorpseRemovalAndNativeNonprecisionKillsQualify(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            DevourGameTest.nativeKill(t, FeedTheVoidGameTest.target(t, 0, 1)); h.assertTrue(t.pickups.isEmpty(), "unweakened actual kill does not qualify");
            var victim = FeedTheVoidGameTest.target(t, 1, 1); var point = victim.position(); weaken(t, t.owner, victim); t.afterDamageReceipt = (d, r) -> { if (d.target().equals(victim.getUUID().toString()) && r.lethal()) victim.discard(); };
            FeedTheVoidGameTest.damage(t, "weapon", victim, 1000); h.assertTrue(victim.isRemoved(), "corpse removed before rules"); h.assertValueEqual(t.pickups.size(), 2, "historical weakened kill still qualifies"); for (var p : t.pickups) near(h, p.position().distanceTo(point), 0, "stored death location survives removal");
            h.assertTrue(status(t, "harvest_cooldown").isPresent() && status(t, "harvest_pending").isEmpty(), "successful completion");
        }
        try (var t = open(h, false)) { kill(t, 0); h.assertValueEqual(t.pickups.size(), 2, "ordinary native nonprecision attack also qualifies"); } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:harvest_live")
    public void currentFragmentAtPhysicalImpactControlsGenerationAndDuplicateSourcesProduceOnePair(GameTestHelper h) throws Exception {
        for (boolean attached : List.of(false, true)) try (var t = open(h, false)) {
            var victim = FeedTheVoidGameTest.target(t, 0, 1); weaken(t, t.owner, victim); FeedTheVoidGameTest.cast(t, false); t.runtime.unbind("harvest");
            if (attached) { t.runtime.bind(fragment(t, "late")); t.runtime.bind(fragment(t, "duplicate")); }
            FeedTheVoidGameTest.contacts(t, 1); h.assertValueEqual(t.pickups.size(), attached ? 2 : 0, "current Fragment with shared reservation");
            if (attached) { t.runtime.unbind("late"); t.runtime.unbind("duplicate"); StarvationGameTest.collect(t, t.pickups.getFirst()); StarvationGameTest.collect(t, t.pickups.getLast()); near(h, t.owner.getHealth(), 240, "existing pair survives generator removal"); }
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:harvest_cooldown", maxTicks = 230)
    public void physicalCollectionAndRebindingDoNotResetTenSecondGenerationCooldown(GameTestHelper h) throws Exception {
        var t = open(h, false);
        try {
            kill(t, 0); StarvationGameTest.collect(t, t.pickups.getFirst()); StarvationGameTest.collect(t, t.pickups.getLast()); t.runtime.unbind("harvest"); t.runtime.bind(fragment(t, "again"));
            RampageGameTest.at(t, 102, () -> { kill(t, 1); h.assertValueEqual(t.pickups.size(), 2, "Breach five seconds does not shorten Harvest ten seconds"); });
            t.finish(202, () -> { kill(t, 2); h.assertValueEqual(t.pickups.size(), 4, "new actual kill after ten seconds generates a new pair"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:harvest_breach")
    public void anotherBreachProducerSharesOnlyBreachCooldownWhileHarvestOrbRemainsIndependent(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) { StarvationGameTest.spawn(t, "void_breach", t.owner); kill(t, 0); h.assertValueEqual(t.pickups.size(), 2, "prior breach plus Harvest Orb only"); h.assertTrue(status(t, "harvest_cooldown").isPresent(), "Orb starts Harvest cooldown even with suppressed Breach"); StarvationGameTest.collect(t, t.pickups.getLast()); near(h, StarvationGameTest.account(t, "super"), .025, "second pickup is real Harvest Orb"); near(h, StarvationGameTest.account(t, "class_a"), 0, "prior breach not collected"); } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:harvest_enhanced")
    public void paidVoidKillThenItsTwoPickupsComposeWithFeedTheVoidWithoutExtraKillEnergy(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) { var victim = FeedTheVoidGameTest.target(t, 0, 1); weaken(t, t.owner, victim); FeedTheVoidGameTest.cast(t, false); FeedTheVoidGameTest.contacts(t, 1); near(h, t.owner.getHealth(), 240, "real Feed activation first");
            StarvationGameTest.collect(t, t.pickups.getFirst()); StarvationGameTest.collect(t, t.pickups.getLast()); near(h, t.owner.getHealth(), 520, "two current enhanced pickup heals"); near(h, DevourGameTest.energy(t, "a"), 0, "initial kill and pickups do not grant active-kill energy"); DevourGameTest.nativeKill(t, DevourGameTest.target(t, 1)); near(h, DevourGameTest.energy(t, "a"), .075, "subsequent actual enhanced Devour kill");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:harvest_rejected")
    public void recipientRemovedAfterActualDeathRejectsBothSpawnsWithoutConsumingCooldown(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) { var victim = FeedTheVoidGameTest.target(t, 0, 1); weaken(t, t.owner, victim); t.afterDamageReceipt = (_, r) -> { if (r.lethal()) t.owner.discard(); };
            FeedTheVoidGameTest.damage(t, "weapon", victim, 1000); h.assertTrue(victim.isDeadOrDying(), "death committed"); h.assertTrue(t.pickups.isEmpty(), "removed private recipient rejects world creation"); h.assertTrue(status(t, "harvest_cooldown").isEmpty() && status(t, "harvest_pending").isEmpty(), "known rejection releases attempt without cooldown");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:harvest_unknown")
    public void unknownReceiptAfterActualOrbCreationStopsBeforeBreachAndNeverReplaysSpawn(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) { var victim = FeedTheVoidGameTest.target(t, 0, 1); weaken(t, t.owner, victim); FeedTheVoidGameTest.cast(t, false); t.failAfterPickup = true;
            var p = t.projectiles.getFirst(); for (int i = 0; i < 50 && t.runtime.failure().isEmpty() && !p.isRemoved(); i++) p.tick(); h.assertTrue(t.runtime.failure().isPresent(), "unknown result faults runtime"); h.assertTrue(victim.isDeadOrDying(), "actual death retained"); h.assertValueEqual(t.pickups.size(), 1, "Orb world write occurred but Breach not invented");
            h.assertTrue(status(t, "harvest_pending").isPresent() && status(t, "harvest_cooldown").isEmpty(), "unknown outcome remains unresolved"); for (int i = 0; i < 3; i++) p.tick(); h.assertValueEqual(t.pickups.size(), 1, "no replayed physical spawn");
        } h.succeed();
    }
}
