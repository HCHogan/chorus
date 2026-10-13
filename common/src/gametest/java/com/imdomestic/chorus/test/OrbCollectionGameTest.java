package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.ArmorChargeGameTest.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class OrbCollectionGameTest {
    static ProjectileGameTest.Harness openCollector(GameTestHelper h) throws Exception {
        var t = open(h, "pickup_collection_inputs");
        try { IncandescentGameTest.bind(t, "collector", "test:orb_collector", ""); return t; }
        catch (Exception | Error error) { t.close(); throw error; }
    }
    static void orbs(ProjectileGameTest.Harness t, int count) { for (int i = 0; i < count; i++) spawn(t); }
    static void collect(ProjectileGameTest.Harness t) {
        t.runtime.start(new RuleEngine.Signal("test:collect_orbs", new EffectEvent(owner(t), owner(t), new BuffInstance.Origin(owner(t), "collector", "", ""), Set.of(), Map.of())));
    }
    static long consumed(ProjectileGameTest.Harness t) { return t.pickups.stream().filter(p -> p.isRemoved()).count(); }
    @GameCase public void physicalOrbBatchStopsWhenNestedArmorChargeReactionsFillThePool(GameTestHelper h) throws Exception {
        try (var t = openCollector(h)) {
            StarvationGameTest.fill(t, "super"); mod(t, "grenade_font", 0); orbs(t, 5); collect(t); ShieldThrowGameTest.settled(t);
            h.assertValueEqual(consumed(t), 3L, "surplus physical Orbs retained"); h.assertValueEqual(charges(t), 3, "three charge rewards");
            near(h, StarvationGameTest.account(t, "super"), 1, "full Super unchanged");
            spend(t, 1); collect(t); ShieldThrowGameTest.settled(t);
            h.assertValueEqual(consumed(t), 4L, "only one newly useful Orb claimed"); h.assertValueEqual(charges(t), 3, "pool refilled");
        } h.succeed();
    }
    @GameCase public void stacksOnStacksLeavesThreeOfFivePhysicalCandidatesUnclaimed(GameTestHelper h) throws Exception {
        try (var t = openCollector(h)) {
            StarvationGameTest.fill(t, "super"); mod(t, "grenade_font", 0); mod(t, "stacks_on_stacks", 0); orbs(t, 5); collect(t); ShieldThrowGameTest.settled(t);
            h.assertValueEqual(consumed(t), 2L, "bonus charge respected before next claim"); h.assertValueEqual(charges(t), 3, "capacity respected");
        } h.succeed();
    }
    @GameCase public void starvationKeepsItsIndependentPermissionAndEachPhysicalOrbTriggersDevour(GameTestHelper h) throws Exception {
        try (var t = openCollector(h)) {
            StarvationGameTest.fill(t, "super"); mod(t, "grenade_font", 0); t.runtime.bind(StarvationGameTest.fragment(t, "starvation", 10)); orbs(t, 5); collect(t); ShieldThrowGameTest.settled(t);
            h.assertValueEqual(consumed(t), 5L, "independent Fragment permits surplus Orbs"); h.assertValueEqual(charges(t), 3, "charge does not overflow");
            near(h, t.owner.getHealth(), 450, "five real Devour heals"); near(h, StarvationGameTest.account(t, "super"), 1, "Super remains capped");
            collect(t); near(h, t.owner.getHealth(), 450, "no duplicate rewards");
        } h.succeed();
    }
    @GameCase public void unknownNestedDevourHealingDoesNotConsumeTheNextPhysicalOrb(GameTestHelper h) throws Exception {
        try (var t = openCollector(h)) {
            t.runtime.bind(StarvationGameTest.fragment(t, "starvation", 10)); orbs(t, 3); t.failAfterHealing = true;
            boolean failed = false; try { collect(t); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent(), "unknown outcome stops runtime");
            h.assertValueEqual(consumed(t), 1L, "remaining candidates were not claimed");
            near(h, t.owner.getHealth(), 170, "actual first heal retained"); near(h, StarvationGameTest.account(t, "super"), .025, "first base reward retained");
        } h.succeed();
    }
}
