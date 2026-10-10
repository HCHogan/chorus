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

public class RampageGameTest {
    static void prepare(JsonObject data) {
        var weapon = ThreadedSpikeGameTest.json("rampage_weapon");
        for (var entry : weapon.entrySet()) {
            if (entry.getKey().equals("version")) continue;
            if (entry.getValue().isJsonArray()) {
                if (!data.has(entry.getKey())) data.add(entry.getKey(), new JsonArray());
                entry.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(entry.getKey()).add(v));
            } else data.add(entry.getKey(), entry.getValue());
        }
    }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static void equip(ProjectileGameTest.Harness t) {
        t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var e = PlayerEquipment.get(t.owner);
        for (String id : List.of("a", "b")) {
            var stack = new ItemStack(Items.DIAMOND_SWORD);
            stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", id.equals("a") ? "normal" : "enhanced")));
            t.owner.getInventory().setItem(0, stack); e.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, e.revision());
        }
        PugilistGameTest.draw(t, "primary");
    }
    static int stacks(ProjectileGameTest.Harness t, String weapon) {
        t.runtime.prepare();
        return state(t).buffs().instances().values().stream().filter(b -> b.definition().tags().contains("chorus_d2:rampage") && b.origin().weapon().equals(weapon)).mapToInt(BuffInstance::count).sum();
    }
    static void counts(ProjectileGameTest.Harness t, int a, int b) {
        t.h.assertValueEqual(stacks(t, "a"), a, "normal weapon layers"); t.h.assertValueEqual(stacks(t, "b"), b, "enhanced weapon layers");
    }
    static void kill(ProjectileGameTest.Harness t, String slot) throws Exception {
        PugilistGameTest.draw(t, slot); var victim = PugilistGameTest.victim(t);
        PugilistGameTest.impact(t, PugilistGameTest.fire(t), victim);
    }
    @FunctionalInterface interface Check { void run() throws Exception; }
    static void at(ProjectileGameTest.Harness t, int tick, Check check) {
        t.h.runAfterDelay(tick, () -> { try { check.run(); } catch (Exception | Error e) { t.close(); throw new RuntimeException(e); } });
    }
    @GameCase(environment = "chorus_gametest:rampage_decay", maxTicks = 325)
    public void physicalKillsBuildBothVariantsAndRealTicksDecayEachLayerWhileStowed(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "rampage", RampageGameTest::prepare, true);
        try {
            equip(t); kill(t, "primary"); kill(t, "secondary"); counts(t, 1, 1);
            at(t, 4, () -> { kill(t, "primary"); near(h, t.receipts.getLast().outgoing().orElseThrow().output().value(), 11, "first tier boosted second kill"); kill(t, "secondary"); counts(t, 2, 2); });
            at(t, 8, () -> { kill(t, "primary"); near(h, t.receipts.getLast().outgoing().orElseThrow().output().value(), 12.1, "second tier boosted third kill"); kill(t, "secondary"); counts(t, 3, 3); });
            at(t, 20, () -> {
                PugilistGameTest.draw(t, "primary"); var victim = t.cow(2.5, 46, 3.5); var shot = PugilistGameTest.fire(t);
                for (int i = 0; i < 20 && !shot.isRemoved(); i++) shot.tick();
                near(h, victim.getHealth(), 86.69, "third tier applies to actual next shot"); victim.discard(); counts(t, 3, 3);
                t.runtime.reload(t.owner);
            });
            at(t, 30, () -> {
                counts(t, 3, 3); h.assertValueEqual(state(t).ammunition().get("a").magazine(), 5, "actual reload does not remove Rampage");
                h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(), 26, "four rounds came from reserves");
                PugilistGameTest.draw(t, "secondary");
            });
            at(t, 100, () -> counts(t, 2, 3)); at(t, 110, () -> counts(t, 2, 2));
            at(t, 200, () -> counts(t, 1, 2)); at(t, 220, () -> counts(t, 1, 1));
            at(t, 285, () -> counts(t, 0, 1)); t.finish(315, () -> counts(t, 0, 0));
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:rampage_snapshot", maxTicks = 115)
    public void shotLaunchedBeforeExpiryKeepsItsTierWhenImpactArrivesAfterStowAndExpiry(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "rampage", RampageGameTest::prepare, true);
        try {
            equip(t); kill(t, "primary"); final LivingEntity[] target = new LivingEntity[1];
            at(t, 88, () -> {
                counts(t, 1, 0); target[0] = t.cow(2.5, 50, 3.5); PugilistGameTest.fire(t); PugilistGameTest.draw(t, "secondary");
                near(h, target[0].getHealth(), 100, "launch has not yet hit");
            });
            at(t, 93, () -> { counts(t, 0, 0); near(h, target[0].getHealth(), 100, "buff expired before the projectile arrived"); });
            t.finish(100, () -> {
                counts(t, 0, 0); near(h, target[0].getHealth(), 89, "expired live Rampage still belongs to its earlier shot");
                h.assertValueEqual(t.hits.getLast().source().weapon(), "a", "stowing retained original weapon identity");
                var id = t.owner.getUUID().toString(); var query = new EffectEvent(id, id, new BuffInstance.Origin(id, "query", "a", ""), Set.of("chorus:weapon_damage"), Map.of());
                near(h, t.runtime.program().calculate(state(t), id, query, "test:weapon_damage", new Measure(10, Unit.DAMAGE), List.of()).output().value(), 10, "a new attack has no expired boost");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void actualUncreditedKillAndPerkRemovedInFlightDoNotGrantStacks(GameTestHelper h) throws Exception {
        for (boolean remove : List.of(false, true)) try (var t = new ProjectileGameTest.Harness(h, "rampage", data -> {
            prepare(data);
            if (!remove) data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action").add("kill_tags", new JsonArray());
        }, true)) {
            equip(t); var victim = PugilistGameTest.victim(t); var shot = PugilistGameTest.fire(t);
            if (remove) {
                var equipment = PlayerEquipment.get(t.owner); t.owner.getInventory().setItem(0, ItemStack.EMPTY);
                equipment.swap(t.owner, "test:primary", 0, equipment.revision());
                h.assertTrue(!t.owner.getInventory().getItem(0).isEmpty(), "actual weapon was returned to inventory");
            }
            PugilistGameTest.impact(t, shot, victim); counts(t, 0, 0);
            h.assertTrue(t.receipts.getLast().lethal(), "actual lethal receipt");
            h.assertValueEqual(state(t).ammunition().get("a").magazine(), 4, "shot remains paid");
        }
        h.succeed();
    }
    @GameCase public void unknownLethalReceiptKeepsSpentAmmoAndDoesNotInventOrReplayAKillGrant(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "rampage", RampageGameTest::prepare, true)) {
            equip(t); var victim = PugilistGameTest.victim(t); t.failAfterDamage = true; var shot = PugilistGameTest.fire(t);
            for (int i = 0; i < 20 && !shot.isRemoved(); i++) shot.tick();
            h.assertTrue(victim.isDeadOrDying() && t.runtime.failure().isPresent(), "lethal world change occurred but its receipt is unknown");
            h.assertTrue(state(t).buffs().instances().isEmpty(), "unknown receipt cannot manufacture kill credit");
            h.assertValueEqual(state(t).ammunition().get("a").magazine(), 4, "accepted ammunition debit retained");
            boolean rejected = false; try { t.runtime.fire(t.owner); } catch (IllegalStateException expected) { rejected = true; }
            h.assertTrue(rejected && t.projectiles.size() == 1 && t.hits.size() == 1, "failed input cannot replay the shot or lethal damage");
        }
        h.succeed();
    }
}
