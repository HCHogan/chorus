package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

/** Real inventory, fire commands, swept contact geometry, damage receipts and per-instance ammunition. */
public class PrecisionAmmoGameTest {
    static final String TT = "triple_tap", FT = "fourth_times_the_charm", D = "chorus_d2:";
    static void prepare(JsonObject data) {
        for (String name : List.of(FT, "precision_ammo_weapon")) for (var e : ThreadedSpikeGameTest.json(name).entrySet()) {
            if (e.getKey().equals("version")) continue;
            if (e.getValue().isJsonArray()) {
                if (!data.has(e.getKey())) data.add(e.getKey(), new JsonArray());
                e.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(e.getKey()).add(v));
            } else data.add(e.getKey(), e.getValue());
        }
    }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        return open(h, _ -> {});
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, java.util.function.Consumer<JsonObject> edit) throws Exception {
        var t = new ProjectileGameTest.Harness(h, TT, data -> { prepare(data); edit.accept(data); }, true);
        t.owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 2.5))); t.owner.setXRot(0); t.owner.setYRot(0);
        t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); t.owner.getFoodData().setFoodLevel(0);
        var e = PlayerEquipment.get(t.owner);
        for (String weapon : List.of("a", "b")) {
            String variant = weapon.equals("a") ? "normal" : "enhanced";
            var stack = new ItemStack(Items.DIAMOND_SWORD);
            stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(weapon, "test:rifle", Map.of(TT, variant, FT, variant)));
            t.owner.getInventory().setItem(0, stack); e.swap(t.owner, weapon.equals("a") ? "test:primary" : "test:secondary", 0, e.revision());
        }
        PugilistGameTest.draw(t, "primary"); return t;
    }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static int magazine(ProjectileGameTest.Harness t, String weapon) { return state(t).ammunition().get(weapon).magazine(); }
    static double count(ProjectileGameTest.Harness t, String perk, String weapon) {
        t.runtime.prepare(); return state(t).buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + perk + "_counter") && b.origin().weapon().equals(weapon)).mapToDouble(b -> b.components().numbers().get("hits")).sum();
    }
    static LivingEntity target(ProjectileGameTest.Harness t, boolean precision) {
        var e = LightweightKnifeGameTest.target(t, precision ? .9 : .5, 1000); String id = e.getUUID().toString();
        t.runtime.bind(new EffectSource("region/" + id, "test:weapon_region", id, new BuffInstance.Origin(id, "region/" + id, "", ""), Set.of(), Map.of(
                "min_x", new Measure(-1, Unit.MULTIPLIER), "max_x", new Measure(2, Unit.MULTIPLIER),
                "min_y", new Measure(.75, Unit.MULTIPLIER), "max_y", new Measure(1.125, Unit.MULTIPLIER),
                "min_z", new Measure(-1, Unit.MULTIPLIER), "max_z", new Measure(2, Unit.MULTIPLIER))));
        return e;
    }
    static void impact(ProjectileGameTest.Harness t, EffectProjectile p) {
        int before = t.hits.size(); for (int i = 0; i < 40 && !p.isRemoved(); i++) p.tick();
        t.h.assertTrue(p.isRemoved() && t.hits.size() == before + 1, "one physical contact expected");
    }
    static void hit(ProjectileGameTest.Harness t, String slot, boolean precision) throws Exception {
        PugilistGameTest.draw(t, slot); var e = target(t, precision); var p = PugilistGameTest.fire(t);
        int before = t.hits.size(); t.h.assertTrue(!p.isRemoved(), "fire became hitscan"); impact(t, p);
        t.h.assertTrue(t.runtime.failure().isEmpty(), "perk runtime failed: " + t.runtime.failure());
        t.h.assertValueEqual(t.hits.get(before).tags().contains("chorus:precision"), precision, "actual contact classification");
        near(t.h, e.getHealth(), precision ? 985 : 990, "contact damage");
        t.runtime.unbind("region/" + e.getUUID()); e.discard();
    }
    @GameCase(environment = "chorus_gametest:precision_ammo_cycles", maxTicks = 80)
    public void repeatedRealPrecisionShotsComposeBothPerksAndReloadAloneConsumesReserves(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            hit(t, "primary", true); hit(t, "secondary", true);
            for (int i = 2; i <= 12; i++) { int shots = i; RampageGameTest.at(t, (i - 1) * 4, () -> {
                hit(t, "primary", true); hit(t, "secondary", true);
                for (String weapon : List.of("a", "b")) {
                    h.assertValueEqual(magazine(t, weapon), 12 - shots + shots / 3 + 2 * (shots / 4), "shared generated ammunition");
                    near(h, count(t, TT, weapon), shots % 3, "triple tap independent counter"); near(h, count(t, FT, weapon), shots % 4, "fourth time independent counter");
                    h.assertValueEqual(state(t).ammunition().get(weapon).reserve().orElseThrow().rounds(), 30, "generation must not debit reserves");
                }
            }); }
            RampageGameTest.at(t, 48, () -> hit(t, "primary", true));
            RampageGameTest.at(t, 49, () -> { PugilistGameTest.draw(t, "primary"); t.runtime.reload(t.owner); });
            t.finish(60, () -> {
                h.assertValueEqual(magazine(t, "a"), 12, "real reload filled three rounds"); h.assertValueEqual(magazine(t, "b"), 10, "stowed magazine unchanged");
                h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(), 27, "manual reload spends reserves");
                near(h, count(t, TT, "a"), 1, "reload neither clears nor advances the new precision counter");
                near(h, count(t, FT, "a"), 1, "reload preserves each perk independently");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:precision_ammo_gap", maxTicks = 140)
    public void realBodyContactDoesNotRefreshGapAndEnhancedCounterSurvivesStowForLonger(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            hit(t, "primary", true); hit(t, "secondary", true);
            RampageGameTest.at(t, 25, () -> { hit(t, "primary", false); near(h, count(t, TT, "a"), 1, "body hit does not reset count"); PugilistGameTest.draw(t, "secondary"); });
            RampageGameTest.at(t, 50, () -> {
                hit(t, "primary", true); hit(t, "secondary", true);
                near(h, count(t, TT, "a"), 1, "normal two-second gap expired despite body contact"); near(h, count(t, TT, "b"), 2, "enhanced three-second gap survives stow");
            });
            RampageGameTest.at(t, 54, () -> {
                hit(t, "primary", true); hit(t, "secondary", true);
                h.assertValueEqual(magazine(t, "a"), 8, "four shots without normal proc"); h.assertValueEqual(magazine(t, "b"), 10, "third enhanced precision generated one");
            });
            RampageGameTest.at(t, 58, () -> { hit(t, "secondary", true); h.assertValueEqual(magazine(t, "b"), 11, "fourth enhanced precision generated two"); });
            t.finish(125, () -> { t.runtime.prepare(); h.assertTrue(state(t).buffs().instances().isEmpty() && state(t).timers().isEmpty(), "counter and receipt cleanup after last gap"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void immuneBodyAndUncreditedPhysicalContactsNeverCount(GameTestHelper h) throws Exception {
        for (String variant : List.of("body", "immune", "uncredited")) try (var t = open(h, data -> {
            if (variant.equals("uncredited")) {
                var tags = new JsonArray(); tags.add("chorus:precision");
                data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(3).getAsJsonObject().getAsJsonObject("action").add("tags", tags);
            }
        })) {
            var e = target(t, !variant.equals("body")); if (variant.equals("immune")) e.setPermanentlyInvulnerable(true);
            impact(t, PugilistGameTest.fire(t)); near(h, count(t, TT, "a"), 0, "unqualified contact cannot count"); near(h, count(t, FT, "a"), 0, "unqualified contact cannot count");
            h.assertValueEqual(magazine(t, "a"), 11, "contact did not refund shot");
            if (variant.equals("immune")) near(h, e.getHealth(), 1000, "immune target stayed healthy");
        }
        h.succeed();
    }
    @GameCase public void removingActualWeaponInFlightPreventsItsPerkFromCountingLaterImpact(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            var e = target(t, true); var p = PugilistGameTest.fire(t); var equipment = PlayerEquipment.get(t.owner);
            t.owner.getInventory().setItem(0, ItemStack.EMPTY); equipment.swap(t.owner, "test:primary", 0, equipment.revision());
            impact(t, p); near(h, e.getHealth(), 985, "accepted physical shot still damages"); near(h, count(t, TT, "a"), 0, "detached perk cannot count");
            h.assertTrue(state(t).timers().isEmpty(), "removed source did not create receipt timers"); h.assertValueEqual(magazine(t, "a"), 11, "accepted debit retained");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:precision_ammo_unknown", maxTicks = 30)
    public void unknownThirdPrecisionReceiptRetainsDamageAndDebitWithoutGeneratingOrReplaying(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            hit(t, "primary", true); RampageGameTest.at(t, 4, () -> hit(t, "primary", true));
            RampageGameTest.at(t, 8, () -> {
                try {
                    var e = target(t, true); t.failAfterDamage = true; var p = PugilistGameTest.fire(t); impact(t, p);
                    h.assertTrue(t.runtime.failure().isPresent(), "world result is unknown"); near(h, e.getHealth(), 985, "actual damage persists");
                    h.assertValueEqual(magazine(t, "a"), 9, "third debit remains without invented generated round");
                    boolean rejected = false; try { t.runtime.fire(t.owner); } catch (IllegalStateException expected) { rejected = true; }
                    p.tick(); h.assertTrue(rejected && t.hits.size() == 3 && t.projectiles.size() == 3, "unknown input must not replay"); h.succeed();
                } finally { t.close(); }
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
