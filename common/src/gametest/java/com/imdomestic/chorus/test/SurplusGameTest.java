package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

public class SurplusGameTest {
    static void prepare(JsonObject data) {
        for (String fixture : List.of("weapon_stats", "surplus_weapon", "wellspring_targets", "wellspring")) {
            var fragment = ThreadedSpikeGameTest.json(fixture);
            for (var entry : fragment.entrySet()) {
                if (entry.getKey().equals("version")) continue;
                if (entry.getValue().isJsonArray()) {
                    if (!data.has(entry.getKey())) data.add(entry.getKey(), new JsonArray());
                    entry.getValue().getAsJsonArray().forEach(e -> data.getAsJsonArray(entry.getKey()).add(e));
                } else data.add(entry.getKey(), entry.getValue());
            }
        }
    }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static String owner(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static void start(ProjectileGameTest.Harness t, boolean wellspring, double grenade, double melee, double clazz) {
        start(t, wellspring, grenade, melee, clazz, false);
    }
    static void start(ProjectileGameTest.Harness t, boolean wellspring, double grenade, double melee, double clazz, boolean secondarySurplus) {
        t.owner.setGameMode(GameType.SURVIVAL); var equipment = PlayerEquipment.get(t.owner);
        for (String id : List.of("a", "b")) {
            var sockets = new HashMap<String, String>(); sockets.put("perk", id.equals("a") || secondarySurplus ? "normal" : "none"); if (id.equals("a") && wellspring) sockets.put("energy", "wellspring");
            var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, id.equals("a") ? "test:rifle" : "test:shotgun", sockets));
            t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
        }
        PugilistGameTest.draw(t, "primary"); WellspringGameTest.select(t, true);
        var origin = new BuffInstance.Origin(owner(t), "input", "", "");
        t.runtime.bind(new EffectSource("fill", "test:wellspring_input", owner(t), origin, Set.of()));
        t.runtime.bind(new EffectSource("spend", "test:surplus_input", owner(t), origin, Set.of()));
        t.runtime.start(new RuleEngine.Signal("test:fill", new EffectEvent(owner(t), owner(t), origin, Set.of(), Map.of(
                "grenade", new Measure(grenade, Unit.CHARGE), "melee", new Measure(melee, Unit.CHARGE), "class", new Measure(clazz, Unit.CHARGE)))));
    }
    static double points(ProjectileGameTest.Harness t, String stat, String weapon) {
        return t.runtime.program().calculate(state(t), owner(t), new EffectEvent(owner(t), weapon, new BuffInstance.Origin(owner(t), weapon, weapon, ""), Set.of(), Map.of()),
                "chorus_d2:weapon_" + stat, new Measure(10, Unit.STAT_POINT), List.of()).trace().stages().get("stat_cap").value();
    }
    static void use(ProjectileGameTest.Harness t, String slot) { t.h.assertValueEqual(t.runtime.useAbility(t.owner, "chorus_d2:" + slot).outcome(), AbilityUse.Outcome.ACCEPTED, "selected ability paid its cost"); }
    static void spend(ProjectileGameTest.Harness t) {
        t.runtime.start(new RuleEngine.Signal("test:spend_ammo", new EffectEvent(owner(t), "a", new BuffInstance.Origin(owner(t), "input", "", ""), Set.of(), Map.of())));
    }
    static void reload(ProjectileGameTest.Harness t) throws Exception {
        t.h.assertValueEqual(t.h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon reload", t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)), 1, "server accepted manual reload");
    }
    @GameCase public void extraChargesCountSeparatelyAndSpendingUpdatesOnlyThePerkWeapon(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "surplus", SurplusGameTest::prepare, true)) {
            start(t, false, 0, 2, 0); near(h, points(t, "handling", "a"), 35, "two melee charges give second tier"); near(h, points(t, "handling", "b"), 10, "other weapon has no perk");
            PugilistGameTest.draw(t, "secondary"); use(t, "melee");
            near(h, points(t, "handling", "a"), 15, "stowed weapon now has one charged ability"); near(h, points(t, "reload", "a"), 20, "reload first tier");
            use(t, "melee"); near(h, points(t, "stability", "a"), 10, "no charges gives no stability bonus");
        }
        h.succeed();
    }
    @GameCase public void actualWellspringKillCompletesAChargeAndImmediatelyRaisesSurplusReloadSpeed(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "surplus", SurplusGameTest::prepare, true)) {
            start(t, true, .999, 1, 1); near(h, points(t, "reload", "a"), 35, "partial grenade does not count");
            var target = PugilistGameTest.victim(t); PugilistGameTest.impact(t, PugilistGameTest.fire(t), target);
            near(h, WellspringGameTest.energy(t, "grenade"), 1, "real kill filled grenade"); near(h, points(t, "reload", "a"), 70, "same weapon now reaches third tier");
            reload(t); near(h, state(t).reloads().get(owner(t)).duration().value(), 1.3, "live stats enter the synthetic archetype timing curve");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:surplus_reload", maxTicks = 85)
    public void acceptedReloadDeadlineStaysFixedWhileTheNextReloadUsesNewCharges(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "surplus", SurplusGameTest::prepare, true);
        try {
            start(t, false, 1, 1, 1); spend(t); reload(t); var first = state(t).reloads().get(owner(t));
            near(h, first.duration().value(), 1.3, "third tier accepted duration"); use(t, "melee");
            near(h, points(t, "reload", "a"), 35, "current stat fell to second tier"); h.assertTrue(first.equals(state(t).reloads().get(owner(t))), "already accepted plan unchanged");
            h.runAfterDelay(25, () -> { try { h.assertValueEqual(state(t).ammunition().get("a").magazine(), 4, "first deadline not reached"); } catch (Throwable e) { t.close(); throw e; } });
            h.runAfterDelay(27, () -> { try {
                h.assertValueEqual(state(t).ammunition().get("a").magazine(), 5, "first reload finished at old duration");
                spend(t); reload(t); near(h, state(t).reloads().get(owner(t)).duration().value(), 1.65, "next accepted duration uses two charges");
            } catch (Exception e) { t.close(); throw new IllegalStateException(e); } catch (Error e) { t.close(); throw e; } });
            h.runAfterDelay(58, () -> { try { h.assertValueEqual(state(t).ammunition().get("a").magazine(), 4, "second reload still pending"); } catch (Throwable e) { t.close(); throw e; } });
            t.finish(62, () -> {
                h.assertValueEqual(state(t).ammunition().get("a").magazine(), 5, "second reload completed");
                h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(), 28, "two reloads transfer two reserve rounds");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
