package com.imdomestic.chorus.test;

import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

/** Synthetic weapon calibration; actual containers, grouped projectiles, receipts and reload input. */
public class RewindRoundsGameTest {
    static final String D = "chorus_d2:rewind_rounds_";
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static int mag(ProjectileGameTest.Harness t, String id) { return state(t).ammunition().get(id).magazine(); }
    static int reserve(ProjectileGameTest.Harness t, String id) { return state(t).ammunition().get(id).reserve().orElseThrow().rounds(); }
    static long cues(ProjectileGameTest.Harness t, String cue) { return t.cues.stream().filter(c -> c.cue().equals("test:" + cue)).count(); }
    static double hits(ProjectileGameTest.Harness t, String weapon) {
        return state(t).buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + "counter") && b.origin().weapon().equals(weapon)).mapToDouble(b -> b.components().numbers().get("hits")).findFirst().orElse(0);
    }
    static void prepare(JsonObject data, int capacity, int pellets, int groups, int reserves) {
        var weapon = ThreadedSpikeGameTest.json("rewind_weapon"); var w = weapon.getAsJsonArray("weapons").get(0).getAsJsonObject();
        var ammo = w.getAsJsonObject("ammunition"); ammo.addProperty("capacity", capacity); ammo.addProperty("magazine", capacity); ammo.getAsJsonObject("reserves").addProperty("rounds", reserves);
        var fire = w.getAsJsonObject("fire"); var original = fire.getAsJsonArray("on_fire"); var steps = new JsonArray();
        for (int i = 0; i < 3; i++) steps.add(original.get(i).deepCopy());
        for (int g = 0; g < groups; g++) {
            var begin = original.get(3).getAsJsonObject().deepCopy(); begin.addProperty("as", "group" + g);
            begin.getAsJsonObject("action").getAsJsonObject("pellets").addProperty("value", pellets); steps.add(begin);
            for (int p = 0; p < pellets; p++) {
                var projectile = original.get(4).getAsJsonObject().deepCopy(); projectile.getAsJsonObject("shot").addProperty("binding", "group" + g);
                projectile.getAsJsonObject("shot").getAsJsonObject("pellet").addProperty("value", p); steps.add(projectile);
            }
        }
        fire.add("on_fire", steps);
        for (var e : weapon.entrySet()) {
            if (e.getKey().equals("version")) continue;
            if (e.getValue().isJsonArray()) { if (!data.has(e.getKey())) data.add(e.getKey(), new JsonArray()); e.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(e.getKey()).add(v)); }
            else data.add(e.getKey(), e.getValue());
        }
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, int capacity, int pellets, int groups, int reserves, boolean adaptive) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "rewind_rounds", data -> prepare(data, capacity, pellets, groups, reserves), true);
        try {
            t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); var equipment = PlayerEquipment.get(t.owner);
            for (String id : List.of("a", "b")) {
                var parameters = Map.of("groups", new Measure(groups, Unit.COUNT), "minimum_fired_fraction", new Measure(.285, Unit.MULTIPLIER),
                        "ignore_seconds", new Measure(.25, Unit.SECOND), "settlement_seconds", new Measure(3, Unit.SECOND),
                        "receipt_seconds", new Measure(3, Unit.SECOND), "adaptive_fraction", new Measure(id.equals("a") ? .14 : .1633, Unit.MULTIPLIER));
                var variant = adaptive ? (id.equals("a") ? "adaptive" : "adaptive_enhanced") : (id.equals("a") ? "normal" : "enhanced");
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", variant), parameters));
                t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            PugilistGameTest.draw(t, "primary"); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static LivingEntity target(ProjectileGameTest.Harness t) {
        var target = t.cow(2.5, 46, 3.5); target.setPos(t.owner.getX(), t.owner.getY() + 6, t.owner.getZ());
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(1000);
        t.h.assertTrue(t.h.getLevel().isPositionEntityTicking(target.blockPosition()), "physical target must be simulated"); return target;
    }
    static List<EffectProjectile> fire(ProjectileGameTest.Harness t, String slot) throws Exception {
        PugilistGameTest.draw(t, slot); int before = t.projectiles.size(); PugilistGameTest.fire(t); return List.copyOf(t.projectiles.subList(before, t.projectiles.size()));
    }
    static void finish(ProjectileGameTest.Harness t, EffectProjectile p) {
        for (int i = 0; i < 25 && !p.isRemoved(); i++) p.tick();
        t.h.assertTrue(p.isRemoved(), "physical projectile did not settle");
    }
    static void shoot(ProjectileGameTest.Harness t, String slot) throws Exception { for (var p : fire(t, slot)) finish(t, p); }
    @GameCase(environment = "chorus_gametest:rewind_variants", maxTicks = 85)
    public void physicalHitsRefillBothVariantsFromTheirOwnReservesWithoutReloading(GameTestHelper h) throws Exception {
        var t = open(h, 10, 1, 1, 30, false);
        try {
            var victim = target(t);
            for (int i = 0; i < 10; i++) RampageGameTest.at(t, i * 6 + 1, () -> { shoot(t, "primary"); shoot(t, "secondary"); });
            t.finish(60, () -> {
                h.assertValueEqual(mag(t, "a"), 6, "normal refill"); h.assertValueEqual(mag(t, "b"), 7, "enhanced refill");
                h.assertValueEqual(reserve(t, "a"), 24, "normal reserve consumption"); h.assertValueEqual(reserve(t, "b"), 23, "enhanced reserve consumption");
                h.assertValueEqual(t.hits.size(), 20, "twenty physical hits"); HealingGameTest.near(h, victim.getHealth(), 800, "actual damage");
                h.assertValueEqual(cues(t, "refilled"), 2L, "one transfer per weapon"); h.assertValueEqual(cues(t, "reloaded"), 0L, "no ordinary reload trigger");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:rewind_miss", maxTicks = 60)
    public void finalPhysicalMissStillRefillsTheStowedWeaponUsingEarlierHits(GameTestHelper h) throws Exception {
        var t = open(h, 4, 1, 1, 30, false);
        try {
            var victim = target(t);
            for (int i = 0; i < 3; i++) RampageGameTest.at(t, i * 6 + 1, () -> shoot(t, "primary"));
            RampageGameTest.at(t, 19, () -> {
                victim.discard(); var last = fire(t, "primary").getFirst(); PugilistGameTest.draw(t, "secondary");
                h.assertValueEqual(mag(t, "a"), 0, "emptying shot waits for its terminal result"); finish(t, last);
                h.assertValueEqual(mag(t, "a"), 2, "prior three hits refill after a known miss"); h.assertValueEqual(mag(t, "b"), 4, "drawn weapon untouched");
            });
            t.finish(23, () -> { h.assertValueEqual(t.hits.size(), 3, "no invented final hit"); h.assertValueEqual(reserve(t, "a"), 28, "real reserve transfer"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:rewind_bolts", maxTicks = 100)
    public void adaptiveBurstUsesEveryActualBoltAndExplicitEnhancedCalibration(GameTestHelper h) throws Exception {
        var t = open(h, 11, 3, 1, 30, true);
        try {
            var victim = target(t);
            for (int i = 0; i < 11; i++) RampageGameTest.at(t, i * 6 + 1, () -> { shoot(t, "primary"); shoot(t, "secondary"); });
            t.finish(67, () -> {
                h.assertValueEqual(t.hits.size(), 66, "three individual physical bolts per shot"); HealingGameTest.near(h, victim.getHealth(), 340, "all bolt damage applied");
                h.assertValueEqual(mag(t, "a"), 5, "ceil of 33 times 0.14"); h.assertValueEqual(mag(t, "b"), 6, "explicit provisional enhanced rate");
                h.assertValueEqual(reserve(t, "a"), 25, "normal reserve consumption"); h.assertValueEqual(reserve(t, "b"), 24, "enhanced reserve consumption");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:rewind_groups", maxTicks = 60)
    public void firstCompletedGroupCannotPrematurelySettleTheEmptyingTrigger(GameTestHelper h) throws Exception {
        var t = open(h, 4, 1, 2, 30, false);
        try {
            target(t); for (int i = 0; i < 3; i++) RampageGameTest.at(t, i * 6 + 1, () -> shoot(t, "primary"));
            RampageGameTest.at(t, 19, () -> {
                var last = fire(t, "primary"); finish(t, last.getFirst());
                h.assertValueEqual(mag(t, "a"), 0, "first group cannot refill"); HealingGameTest.near(h, hits(t, "a"), 7, "first group's hit is retained");
                finish(t, last.getLast()); h.assertValueEqual(mag(t, "a"), 4, "all eight hits counted and base capacity caps refill");
            });
            t.finish(23, () -> { h.assertValueEqual(t.hits.size(), 8, "both groups dealt real damage"); h.assertValueEqual(cues(t, "refilled"), 1L, "one final transfer"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:rewind_reload", maxTicks = 70)
    public void actualManualReloadInvalidatesOutstandingEmptyingFire(GameTestHelper h) throws Exception {
        var t = open(h, 4, 1, 1, 30, false); var pending = new ArrayList<EffectProjectile>();
        try {
            target(t); for (int i = 0; i < 3; i++) RampageGameTest.at(t, i * 6 + 1, () -> shoot(t, "primary"));
            RampageGameTest.at(t, 19, () -> { pending.add(fire(t, "primary").getFirst()); pending.getFirst().setDeltaMovement(Vec3.ZERO); });
            RampageGameTest.at(t, 20, () -> h.assertValueEqual(h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon reload", t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)), 1, "manual reload input"));
            RampageGameTest.at(t, 26, () -> {
                h.assertValueEqual(mag(t, "a"), 4, "manual reload completed first"); HealingGameTest.near(h, hits(t, "a"), 0, "reload resets count");
                pending.getFirst().setDeltaMovement(new Vec3(0, 1, 0)); finish(t, pending.getFirst());
                h.assertValueEqual(mag(t, "a"), 4, "late group cannot repeat an old emptying trigger"); HealingGameTest.near(h, hits(t, "a"), 1, "late hit follows declared post-reload processing policy");
            });
            t.finish(30, () -> { h.assertValueEqual(cues(t, "reloaded"), 1L, "one actual reload"); h.assertValueEqual(cues(t, "refilled"), 1L, "only manual ammo transfer"); h.assertValueEqual(reserve(t, "a"), 26, "no extra transfer"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:rewind_replace", maxTicks = 60)
    public void realPerkReplacementClearsPendingIdentityBeforeOldProjectileHits(GameTestHelper h) throws Exception {
        var t = open(h, 4, 1, 1, 30, false);
        try {
            target(t); for (int i = 0; i < 3; i++) RampageGameTest.at(t, i * 6 + 1, () -> shoot(t, "primary"));
            RampageGameTest.at(t, 19, () -> { var last = fire(t, "primary").getFirst(); ReconstructionGameTest.replace(t, "primary", "none"); finish(t, last); });
            t.finish(23, () -> {
                h.assertValueEqual(t.hits.size(), 4, "old projectile still deals damage"); h.assertValueEqual(mag(t, "a"), 0, "removed perk cannot refill");
                h.assertValueEqual(cues(t, "refilled"), 0L, "no removed-source reward"); h.assertTrue(state(t).buffs().instances().values().stream().noneMatch(b -> b.origin().weapon().equals("a") && b.definition().id().startsWith(D)), "removed source retained private state");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:rewind_unknown", maxTicks = 60)
    public void unknownReactionKeepsTheLimitedReserveTransferWithoutReplaying(GameTestHelper h) throws Exception {
        var t = open(h, 4, 1, 1, 2, false);
        try {
            target(t); for (int i = 0; i < 3; i++) RampageGameTest.at(t, i * 6 + 1, () -> shoot(t, "primary"));
            RampageGameTest.at(t, 19, () -> {
                var last = fire(t, "primary").getFirst(); t.onCue = cue -> { if (cue.cue().equals("test:refilled")) throw new IllegalStateException("Injected unknown Rewind reaction"); };
                finish(t, last);
            });
            h.runAfterDelay(23, () -> { try (t) {
                h.assertTrue(t.runtime.failure().isPresent(), "missing injected reaction failure");
                h.assertValueEqual(mag(t, "a"), 2, "known transfer retained"); h.assertValueEqual(reserve(t, "a"), 0, "limited reserves consumed once");
                long before = cues(t, "refilled"); t.projectiles.forEach(EffectProjectile::tick); h.assertValueEqual(cues(t, "refilled"), before, "unknown reaction replayed"); h.succeed();
            } });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
