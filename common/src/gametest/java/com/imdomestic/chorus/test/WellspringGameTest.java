package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** Actual weapon and projectile integration against explicitly synthetic receiver accounts. */
public class WellspringGameTest {
    static void prepare(JsonObject data) {
        for (String fixture : List.of("wellspring_targets", "pugilist_weapon")) {
            var fragment = JsonParser.parseString(ThreadedSpikeGameTest.json(fixture).toString().replace("chorus_d2:pugilist", "chorus_d2:wellspring")).getAsJsonObject();
            for (var entry : fragment.entrySet()) {
                if (entry.getKey().equals("version")) continue;
                if (entry.getValue().isJsonArray()) {
                    if (!data.has(entry.getKey())) data.add(entry.getKey(), new JsonArray());
                    entry.getValue().getAsJsonArray().forEach(e -> data.getAsJsonArray(entry.getKey()).add(e));
                } else data.add(entry.getKey(), entry.getValue());
            }
        }
        for (String id : List.of("test:projectile", "test:power")) { var empty = new JsonObject(); empty.addProperty("id", id); data.getAsJsonArray("bundles").add(empty); }
    }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static String owner(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static void select(ProjectileGameTest.Harness t, boolean multi) {
        t.runtime.abilities(new AbilityChange(owner(t), state(t).abilities().getOrDefault(owner(t), AbilityLoadout.EMPTY), new AbilityLoadout(Map.of(
                "chorus_d2:grenade", "test:grenade", "chorus_d2:melee", multi ? "test:melee_double" : "test:melee", "chorus_d2:class", "test:class", "chorus_d2:super", "test:super"))));
    }
    static void start(ProjectileGameTest.Harness t, boolean multi, double grenade, double melee, double clazz) {
        PugilistGameTest.equip(t); select(t, multi);
        var origin = new BuffInstance.Origin(owner(t), "input", "", ""); t.runtime.bind(new EffectSource("input", "test:wellspring_input", owner(t), origin, Set.of()));
        t.runtime.start(new RuleEngine.Signal("test:fill", new EffectEvent(owner(t), owner(t), origin, Set.of(), Map.of(
                "grenade", new Measure(grenade, Unit.CHARGE), "melee", new Measure(melee, Unit.CHARGE), "class", new Measure(clazz, Unit.CHARGE)))));
    }
    static double energy(ProjectileGameTest.Harness t, String name) { return state(t).resources().get(new ResourceState.Key(owner(t), "test:" + name + "_energy")).value(); }
    @GameCase public void actualWeaponKillsSplitNormalAndEnhancedEnergyAcrossThreeRecipients(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "wellspring", WellspringGameTest::prepare, true)) {
            start(t, false, 0, 0, 0); var target = PugilistGameTest.victim(t); PugilistGameTest.impact(t, PugilistGameTest.fire(t), target);
            near(h, energy(t, "grenade"), .032 / 3 * .75, "normal grenade share"); near(h, energy(t, "melee"), .032 / 3 * .8, "normal melee share");
            near(h, energy(t, "class"), .032 / 3 * .5, "normal synthetic class share");
            PugilistGameTest.draw(t, "secondary"); target = PugilistGameTest.victim(t); PugilistGameTest.impact(t, PugilistGameTest.fire(t), target);
            near(h, energy(t, "grenade"), .068 / 3 * .75, "enhanced source adds only its own share"); near(h, energy(t, "melee"), .068 / 3 * .8, "enhanced melee");
            near(h, energy(t, "class"), .068 / 3 * .5, "enhanced class"); near(h, energy(t, "super"), 0, "super excluded");
            h.assertValueEqual(state(t).ammunition().get("a").magazine(), 4, "normal shot paid ammunition"); h.assertValueEqual(state(t).ammunition().get("b").magazine(), 4, "enhanced shot paid ammunition");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:wellspring_split", maxTicks = 40) public void serverProjectileTickKeepsTheOriginalDenominatorWhenFirstPoolFills(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "wellspring", WellspringGameTest::prepare, true);
        try {
            start(t, false, .999, 0, 0); var target = PugilistGameTest.victim(t); var projectile = PugilistGameTest.fire(t);
            t.finish(10, () -> {
                h.assertTrue(projectile.isRemoved() && target.isDeadOrDying(), "server tick confirmed physical kill");
                near(h, energy(t, "grenade"), 1, "first pool caps"); near(h, energy(t, "melee"), .032 / 3 * .8, "second retains one third");
                near(h, energy(t, "class"), .032 / 3 * .5, "third retains one third; overflow not redistributed");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void extraChargePolicyAndInFlightSelectionUseCurrentBaseAccounts(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "wellspring", WellspringGameTest::prepare, true)) {
            start(t, true, 0, 1, 1); var target = PugilistGameTest.victim(t); PugilistGameTest.impact(t, PugilistGameTest.fire(t), target);
            near(h, energy(t, "grenade"), .032 * .75, "only uncharged ability gets full base share");
            near(h, energy(t, "melee_double"), 1 + .032 / 3 * .8, "explicit extra-charge third policy");
            PugilistGameTest.draw(t, "secondary"); target = PugilistGameTest.victim(t); var projectile = PugilistGameTest.fire(t);
            select(t, false); PugilistGameTest.impact(t, projectile, target);
            near(h, energy(t, "grenade"), .032 * .75 + .036 / 2 * .75, "new selection is included at impact");
            near(h, energy(t, "melee"), .036 / 2 * .8, "new base account receives gain"); near(h, energy(t, "melee_double"), 1 + .032 / 3 * .8, "old pool retained without moving energy");
        }
        h.succeed();
    }
}
