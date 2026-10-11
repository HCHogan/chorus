package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** Existing physical Pugilist weapon path feeding a synthetic linked melee, with no perk-specific routing branch. */
public class AbilityRechargeGameTest {
    static final String SLOT = "chorus_d2:melee", USES = "test:charges", METER = "test:progress";
    static void prepare(JsonObject data) {
        PugilistGameTest.prepare(data);
        for (String name : List.of("linked_recharge", "ability_recharge_inputs")) {
            for (var entry : ThreadedSpikeGameTest.json(name).entrySet()) {
                if (entry.getKey().equals("version")) continue;
                if (!data.has(entry.getKey())) data.add(entry.getKey(), new JsonArray());
                entry.getValue().getAsJsonArray().forEach(value -> data.getAsJsonArray(entry.getKey()).add(value));
            }
        }
    }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "threaded_spike", AbilityRechargeGameTest::prepare, true);
        EnergyGainGameTest.start(t); PugilistGameTest.equip(t); PugilistGameTest.select(t, "test:linked_ability");
        String owner = PugilistGameTest.owner(t);
        t.runtime.bind(new EffectSource("route-input", "test:route_inputs", owner, new BuffInstance.Origin(owner, "route-input", "", ""), Set.of()));
        return t;
    }
    static void grant(ProjectileGameTest.Harness t, double amount) {
        String owner = PugilistGameTest.owner(t);
        t.runtime.start(new RuleEngine.Signal("test:route_fixed", new EffectEvent(owner, owner,
                new BuffInstance.Origin(owner, "route-input", "", ""), Set.of(), Map.of("amount", new Measure(amount, Unit.CHARGE)))));
    }
    static AbilityUse.Receipt use(GameTestHelper h, ProjectileGameTest.Harness t, AbilityUse.Outcome expected) {
        var r = t.runtime.useAbility(t.owner, SLOT); h.assertValueEqual(r.outcome(), expected, "routed melee input"); return r;
    }
    static void healthy(GameTestHelper h, ProjectileGameTest.Harness t) {
        t.runtime.prepare(); h.assertTrue(t.runtime.failure().isEmpty() && t.runtime.state().idle(), "routed recharge runtime failed: " + t.runtime.failure());
    }
    @GameCase public void physicalPugilistKillCompletesTheCycleAndEnablesTwoActualCasts(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED); grant(t, .98);
            near(h, PugilistGameTest.energy(t, METER), .98, "routed grant did not reach progress"); use(h, t, AbilityUse.Outcome.INSUFFICIENT_ENERGY);
            var target = PugilistGameTest.victim(t); PugilistGameTest.impact(t, PugilistGameTest.fire(t), target);
            near(h, PugilistGameTest.energy(t, USES), 2, "actual Pugilist kill did not refill linked uses"); near(h, PugilistGameTest.energy(t, METER), 0, "cycle was not consumed");
            near(h, t.owner.getHealth(), 14, "two casts and one completion did not reach native health"); grant(t, .5);
            near(h, PugilistGameTest.energy(t, METER), 0, "full usable account banked a second cycle");
            use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.INSUFFICIENT_ENERGY);
            near(h, t.owner.getHealth(), 16, "restored uses did not execute twice"); healthy(h, t);
        }
        h.succeed();
    }
    @GameCase public void anInFlightWeaponKillUsesTheNewlySelectedRechargeDestination(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            use(h, t, AbilityUse.Outcome.ACCEPTED); grant(t, .3);
            var target = PugilistGameTest.victim(t); var shot = PugilistGameTest.fire(t); PugilistGameTest.select(t, "test:other_route"); PugilistGameTest.impact(t, shot, target);
            near(h, PugilistGameTest.energy(t, METER), .3, "old route received in-flight kill energy");
            near(h, PugilistGameTest.energy(t, "test:other_progress"), .01, "new route did not use its own CES");
            near(h, PugilistGameTest.energy(t, "test:other_uses"), 0, "partial cycle was credited as usable energy"); healthy(h, t);
        }
        h.succeed();
    }
    @GameCase public void replacementRefundReturnsToThePaidUseAccountWhileKillsStillFeedBaseRecharge(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            use(h, t, AbilityUse.Outcome.ACCEPTED); grant(t, .5); String owner = PugilistGameTest.owner(t);
            t.runtime.bind(new EffectSource("override", "test:route_override_source", owner, new BuffInstance.Origin(owner, "override", "", ""), Set.of()));
            var cast = use(h, t, AbilityUse.Outcome.ACCEPTED);
            h.assertValueEqual(cast.cost().orElseThrow().receipt().account().resource(), USES, "replacement paid a progress or alternate account");
            near(h, PugilistGameTest.energy(t, USES), 1, "refund missed actual cost account"); near(h, PugilistGameTest.energy(t, METER), .5, "refund changed cycle progress");
            var target = PugilistGameTest.victim(t); PugilistGameTest.impact(t, PugilistGameTest.fire(t), target);
            near(h, PugilistGameTest.energy(t, METER), .52, "cast override redirected base recharge");
            near(h, PugilistGameTest.energy(t, "test:other_progress"), 0, "replacement's declared route received energy"); healthy(h, t);
        }
        h.succeed();
    }
    @GameCase public void unknownCompletionAfterAPhysicalKillKeepsActualHealthAndBothAccountWrites(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED); grant(t, .98);
            var target = PugilistGameTest.victim(t); var shot = PugilistGameTest.fire(t); t.failAfterHealing = true;
            try { for (int i = 0; i < 20 && !shot.isRemoved(); i++) shot.tick(); } catch (IllegalStateException expected) { /* native heal already happened */ }
            h.assertTrue(target.isDeadOrDying(), "physical kill missing"); h.assertTrue(t.runtime.failure().isPresent(), "unknown completion did not stop runtime");
            near(h, PugilistGameTest.energy(t, USES), 2, "restored uses rolled back"); near(h, PugilistGameTest.energy(t, METER), 0, "consumed cycle rolled back");
            near(h, t.owner.getHealth(), 14, "native healing was lost"); t.runtime.prepare(); near(h, t.owner.getHealth(), 14, "native healing replayed");
        }
        h.succeed();
    }
}
