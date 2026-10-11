package com.imdomestic.chorus.test;

import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageFacts;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;

/** Real nonlethal damage instances, kill reset, capacity consumers and explicitly synthetic stat curves. */
public class FailDeadlyGameTest {
    static String owner(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static void prepare(JsonObject data, int capacity, boolean twoInstances) {
        for (String name : List.of("fail_deadly_weapon", "fail_deadly_test_calibration", "weapon_stats", "reload_insert_rounds", "overflow", "ammo_pickups")) {
            var fragment = ThreadedSpikeGameTest.json(name);
            if (name.equals("fail_deadly_weapon")) {
                var weapon = fragment.getAsJsonArray("weapons").get(0).getAsJsonObject();
                var ammo = weapon.getAsJsonObject("ammunition"); ammo.addProperty("capacity", capacity); ammo.addProperty("magazine", capacity);
                if (twoInstances) {
                    // Two separately acknowledged damage components from one physical impact, not a complete rocket prototype.
                    var impact = weapon.getAsJsonObject("fire").getAsJsonArray("on_fire").get(3).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonArray("do");
                    impact.add(impact.get(0).deepCopy());
                }
            }
            for (var entry : fragment.entrySet()) {
                if (entry.getKey().equals("version")) continue;
                if (entry.getValue().isJsonArray()) { if (!data.has(entry.getKey())) data.add(entry.getKey(), new JsonArray()); entry.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(entry.getKey()).add(v)); }
                else data.add(entry.getKey(), entry.getValue());
            }
        }
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, int capacity, String perk, String overflow, boolean twoInstances) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "fail_deadly", d -> prepare(d, capacity, twoInstances), true);
        try {
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX() + 2.5, t.owner.getY(), t.owner.chunkPosition().getMinBlockZ() + 6.5);
            t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); var equipment = PlayerEquipment.get(t.owner);
            for (String id : List.of("a", "b")) {
                var parameters = Map.of("receipt_seconds", new Measure(1, Unit.SECOND), "enhanced_range", new Measure(18, Unit.STAT_POINT), "enhanced_aim_assist", new Measure(6, Unit.STAT_POINT));
                var stack = new ItemStack(Items.DIAMOND_SWORD);
                stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", id.equals("a") ? perk : "enhanced", "overflow", id.equals("a") ? overflow : "none"), parameters));
                t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            PugilistGameTest.draw(t, "primary"); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static int capacity(ProjectileGameTest.Harness t, String weapon) { return t.runtime.program().ammoCapacity(state(t), weapon).capacity(); }
    static int mag(ProjectileGameTest.Harness t, String weapon) { return state(t).ammunition().get(weapon).magazine(); }
    static int reserve(ProjectileGameTest.Harness t, String weapon) { return state(t).ammunition().get(weapon).reserve().orElseThrow().rounds(); }
    static double stacks(ProjectileGameTest.Harness t, String weapon) { return state(t).buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:fail_deadly_state") && b.origin().weapon().equals(weapon)).map(b -> b.components().numbers().get("hits")).findFirst().orElse(0d); }
    static double stat(ProjectileGameTest.Harness t, String weapon, String name) {
        return t.runtime.program().calculate(state(t), owner(t), new EffectEvent(owner(t), owner(t), new BuffInstance.Origin(owner(t), "query", weapon, ""), Set.of(), Map.of()), "chorus_d2:weapon_" + name, new Measure(10, Unit.STAT_POINT), List.of()).output().value();
    }
    static void impact(ProjectileGameTest.Harness t, EffectProjectile shot, LivingEntity victim, double damage) {
        float before = victim.getHealth(); for (int i = 0; i < 20 && !shot.isRemoved(); i++) shot.tick();
        t.h.assertTrue(shot.isRemoved() && victim.isAlive(), "physical nonlethal impact"); HealingGameTest.near(t.h, victim.getHealth(), before - damage, "actual health loss"); victim.discard();
    }
    static void hit(ProjectileGameTest.Harness t, double damage) throws Exception { var victim = ChainReactionGameTest.target(t, 0, 1000); impact(t, PugilistGameTest.fire(t), victim, damage); }
    static void fiveHits(ProjectileGameTest.Harness t) throws Exception {
        hit(t, 10); for (int tick : List.of(4, 8, 12, 16)) RampageGameTest.at(t, tick, () -> hit(t, 10));
    }
    @GameCase(environment = "chorus_gametest:fail_deadly_stats", maxTicks = 95)
    public void physicalDamageExpandsBothVariantsWithoutRefillingAndProgressOutlivesReceiptHistory(GameTestHelper h) throws Exception {
        var t = open(h, 5, "normal", "none", false);
        try {
            fiveHits(t);
            RampageGameTest.at(t, 18, () -> { h.assertValueEqual(capacity(t, "a"), 10, "five ordinary instances double capacity"); h.assertValueEqual(mag(t, "a"), 0, "capacity does not create ammo"); HealingGameTest.near(h, stat(t, "a", "range"), 25, "normal range endpoint"); HealingGameTest.near(h, stat(t, "a", "aim_assist"), 15, "normal aim endpoint"); PugilistGameTest.draw(t, "secondary"); });
            for (int tick : List.of(20, 24, 28, 32, 36)) RampageGameTest.at(t, tick, () -> hit(t, 10));
            RampageGameTest.at(t, 38, () -> { h.assertValueEqual(capacity(t, "b"), 10, "enhanced capacity"); HealingGameTest.near(h, stat(t, "b", "range"), 28, "explicit synthetic enhanced range"); HealingGameTest.near(h, stat(t, "b", "aim_assist"), 16, "explicit synthetic enhanced aim"); TimelostMagazineGameTest.command(t, "chorus weapon reload"); });
            t.finish(80, () -> { h.assertValueEqual(mag(t, "b"), 10, "manual reload fills expanded base"); h.assertValueEqual(reserve(t, "b"), 20, "own reserves pay every round"); h.assertValueEqual(capacity(t, "a"), 10, "stowed progress retained"); h.assertValueEqual(state(t).ammunition().get("a").capacity(), 5, "stable unmodified baseline");
                h.assertTrue(state(t).buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("chorus_d2:fail_deadly_receipts")), "bounded identity history expired without removing progression"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:fail_deadly_rocket")
    public void twoDistinctDamageReceiptsFromOnePhysicalRocketTaggedImpactReachTheTwoStackCap(GameTestHelper h) throws Exception {
        try (var t = open(h, 1, "rocket", "none", true)) {
            hit(t, 20); h.assertValueEqual(t.receipts.size(), 2, "two separate actual damage instances"); h.assertValueEqual(stacks(t, "a"), 2d, "counts instances rather than paid shots"); h.assertValueEqual(capacity(t, "a"), 2, "two stacks allow two rockets"); h.assertValueEqual(mag(t, "a"), 0, "only one paid shot");
            h.assertTrue(t.runtime.failure().isEmpty(), "rocket profile failed");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:fail_deadly_bipod", maxTicks = 30)
    public void bipodCalibratedBaseUsesThreeRoundsAtTwoInstancesAndFourAtFourInstances(GameTestHelper h) throws Exception {
        var t = open(h, 2, "bipod", "none", true);
        try {
            hit(t, 20); h.assertValueEqual(stacks(t, "a"), 2d, "first two instances"); h.assertValueEqual(capacity(t, "a"), 3, "Bipod two stack mapping");
            RampageGameTest.at(t, 4, () -> hit(t, 20));
            t.finish(8, () -> { h.assertValueEqual(stacks(t, "a"), 4d, "second pair"); h.assertValueEqual(capacity(t, "a"), 4, "Bipod four stack mapping"); h.assertValueEqual(mag(t, "a"), 0, "two actual shots paid"); h.assertValueEqual(t.receipts.size(), 4, "four distinct damage results"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:fail_deadly_overflow", maxTicks = 55)
    public void physicalBrickUsesExpandedCapacityAndKillResetsBonusesWithoutDeletingOverflowOrReplayingOldFacts(GameTestHelper h) throws Exception {
        var t = open(h, 5, "normal", "enhanced", false); var killFacts = new ArrayList<RuleEngine.Signal>();
        try {
            t.runtime.bind(new EffectSource("factory", "chorus_d2:ammo_pickups", owner(t), new BuffInstance.Origin(owner(t), "drop", "", ""), Set.of(), Map.of("lifetime", new Measure(10, Unit.SECOND), "radius", new Measure(.5, Unit.METER)))); fiveHits(t);
            RampageGameTest.at(t, 17, () -> OverflowGameTest.spawn(t, "special", t.owner));
            RampageGameTest.at(t, 21, () -> { h.assertTrue(t.pickups.getLast().isRemoved(), "private brick actually collected"); h.assertValueEqual(mag(t, "a"), 22, "enhanced Overflow uses expanded ten round baseline"); h.assertValueEqual(reserve(t, "a"), 9, "one collected and twenty two transferred"); });
            RampageGameTest.at(t, 22, () -> { TimelostMagazineGameTest.kill(t); killFacts.addAll(DamageFacts.from(t.hits.getLast(), t.receipts.getLast()));
                h.assertValueEqual(stacks(t, "a"), 0d, "weapon kill clears all stacks"); h.assertValueEqual(capacity(t, "a"), 5, "base restored"); HealingGameTest.near(h, stat(t, "a", "range"), 10, "stat bonus cleared"); h.assertValueEqual(mag(t, "a"), 21, "already loaded overflow retained"); killFacts.forEach(t.runtime::start); h.assertValueEqual(stacks(t, "a"), 0d, "lethal hit replay cannot rebuild a stack"); });
            RampageGameTest.at(t, 26, () -> { hit(t, 10); killFacts.forEach(t.runtime::start); });
            t.finish(32, () -> { h.assertValueEqual(stacks(t, "a"), 1d, "old death replay cannot clear the new cycle"); h.assertValueEqual(capacity(t, "a"), 6, "new cycle bonus"); h.assertValueEqual(mag(t, "a"), 20, "only real shots consume ammo"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:fail_deadly_shells", maxTicks = 50)
    public void fullShotgunProgressChangesEachActualInsertionAndKeepsReserveConservation(GameTestHelper h) throws Exception {
        var t = open(h, 5, "shotgun", "none", false);
        try {
            fiveHits(t); RampageGameTest.at(t, 17, () -> { TimelostMagazineGameTest.command(t, "chorus weapon reload"); h.assertValueEqual(state(t).reloads().get(owner(t)).portion().orElseThrow().rounds(), 2, "calibrated extra shell accepted"); });
            RampageGameTest.at(t, 22, () -> { h.assertValueEqual(mag(t, "a"), 2, "first actual insertion contains two rounds"); h.assertValueEqual(reserve(t, "a"), 28, "first insertion costs two reserve rounds"); });
            t.finish(35, () -> { h.assertValueEqual(mag(t, "a"), 10, "five actual insertions fill expanded capacity"); h.assertValueEqual(reserve(t, "a"), 20, "no generated shells"); h.assertValueEqual(TimelostMagazineGameTest.cues(t, "reloaded"), 5L, "five insertion completion facts"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:fail_deadly_detach", maxTicks = 45)
    public void stowAllowsLatePhysicalHitsButActualPerkReplacementClearsProgressAndEligibility(GameTestHelper h) throws Exception {
        var t = open(h, 5, "normal", "none", false);
        try {
            hit(t, 10); RampageGameTest.at(t, 4, () -> { var victim = ChainReactionGameTest.target(t, 0, 1000); var shot = PugilistGameTest.fire(t); PugilistGameTest.draw(t, "secondary"); impact(t, shot, victim, 10); h.assertValueEqual(stacks(t, "a"), 2d, "stowed weapon receives its late hit"); h.assertValueEqual(stacks(t, "b"), 0d, "drawn weapon cannot steal credit"); });
            RampageGameTest.at(t, 8, () -> { PugilistGameTest.draw(t, "primary"); var victim = ChainReactionGameTest.target(t, 0, 1000); var shot = PugilistGameTest.fire(t); ReconstructionGameTest.replace(t, "primary", "none"); PugilistGameTest.draw(t, "secondary"); impact(t, shot, victim, 10); });
            t.finish(30, () -> { h.assertValueEqual(stacks(t, "a"), 0d, "removed perk cannot regain progress"); h.assertValueEqual(capacity(t, "a"), 5, "source cleanup removes bonus"); h.assertTrue(state(t).buffs().instances().isEmpty(), "private state and receipts removed"); h.assertValueEqual(t.receipts.size(), 3, "all three physical shots still damaged"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:fail_deadly_unknown")
    public void unknownDamageOutcomeKeepsActualHealthLossWithoutInventingProgressOrReplayingDamage(GameTestHelper h) throws Exception {
        try (var t = open(h, 5, "normal", "none", false)) {
            t.failAfterDamage = true; var victim = ChainReactionGameTest.target(t, 0, 1000); var shot = PugilistGameTest.fire(t);
            for (int i = 0; i < 20 && !shot.isRemoved(); i++) shot.tick();
            h.assertTrue(t.runtime.failure().isPresent() && shot.isRemoved(), "unknown outcome stops runtime and consumes terminal projectile"); HealingGameTest.near(h, victim.getHealth(), 990, "committed damage retained"); h.assertValueEqual(stacks(t, "a"), 0d, "no acknowledged damage fact means no invented stack");
            shot.tick(); MinecraftEffectRuntime.tick(h.getLevel()); HealingGameTest.near(h, victim.getHealth(), 990, "damage never replays"); h.assertValueEqual(t.hits.size(), 1, "only one world operation");
        } h.succeed();
    }
}
