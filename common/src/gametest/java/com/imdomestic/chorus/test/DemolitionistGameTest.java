package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

public class DemolitionistGameTest {
    static final String ENERGY = "chorus_d2:grenade_energy", COOLDOWN = "chorus_d2:demolitionist_cooldown";
    static void prepare(JsonObject data) {
        for (String fixture : List.of("character_stats", "grenade_energy", "arcbolt_energy", "demolitionist_weapon", "kill_clip", "clown_cartridge")) {
            var fragment = JsonParser.parseString(ThreadedSpikeGameTest.json(fixture).toString().replace("\"test-1\"", "\"compendium-2026-10-05\"")).getAsJsonObject();
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
    static void select(ProjectileGameTest.Harness t, String ability) {
        t.runtime.abilities(new AbilityChange(owner(t), state(t).abilities().getOrDefault(owner(t), AbilityLoadout.EMPTY), new AbilityLoadout(Map.of("chorus_d2:grenade", "test:" + ability))));
    }
    static void equip(ProjectileGameTest.Harness t) {
        t.owner.setGameMode(GameType.SURVIVAL); var e = PlayerEquipment.get(t.owner);
        for (String id : List.of("a", "b")) {
            var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, id.equals("a") ? "test:rifle" : "test:shotgun",
                    Map.of("perk", id.equals("a") ? "normal" : "enhanced", "reload_perk", id.equals("a") ? "kill_clip" : "clown")));
            t.owner.getInventory().setItem(0, stack); e.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, e.revision());
        }
        PugilistGameTest.draw(t, "primary"); t.runtime.bind(new EffectSource("demo-input", "test:demo_inputs", owner(t), new BuffInstance.Origin(owner(t), "input", "", ""), Set.of()));
    }
    static int mag(ProjectileGameTest.Harness t, String id) { return state(t).ammunition().get(id).magazine(); }
    static int reserves(ProjectileGameTest.Harness t, String id) { return state(t).ammunition().get(id).reserve().orElseThrow().rounds(); }
    static boolean buff(ProjectileGameTest.Harness t, String id, String weapon) { return state(t).buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals(id) && b.origin().weapon().equals(weapon)); }
    static void input(ProjectileGameTest.Harness t, String type, String target, String measurement, double value, Unit unit) {
        t.runtime.start(new RuleEngine.Signal("test:" + type, new EffectEvent(owner(t), target, new BuffInstance.Origin(owner(t), "input", "", ""), Set.of(), Map.of(measurement, new Measure(value, unit)))));
    }
    static void use(ProjectileGameTest.Harness t) { t.h.assertValueEqual(t.runtime.useAbility(t.owner, "chorus_d2:grenade").outcome(), AbilityUse.Outcome.ACCEPTED, "accepted actual grenade input"); }
    @GameCase public void actualWeaponKillsRestoreGrenadeAndAbilityRefillsDoNotTriggerKillClipOrClown(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "demolitionist", DemolitionistGameTest::prepare, true)) {
            // Synthetic ability body tests accepted use; this is not an Arcbolt trajectory definition.
            select(t, "grenade"); use(t); equip(t); input(t, "stat", owner(t), "stat", 100, Unit.STAT_POINT);
            var first = PugilistGameTest.victim(t); PugilistGameTest.impact(t, PugilistGameTest.fire(t), first);
            near(h, state(t).resources().get(new ResourceState.Key(owner(t), ENERGY)).value(), .0675, "normal rifle gain at 100 grenade stat and .75 CES");
            h.assertTrue(buff(t, "chorus_d2:kill_clip_window", "a"), "real weapon kill opened reload window");
            h.assertValueEqual(t.runtime.useAbility(t.owner, "chorus_d2:grenade").outcome(), AbilityUse.Outcome.INSUFFICIENT_ENERGY, "failed grenade cannot refill");
            h.assertValueEqual(mag(t, "a"), 4, "rejected use kept spent ammo");
            select(t, "free_grenade"); use(t); h.assertValueEqual(mag(t, "a"), 5, "accepted use refills held weapon"); h.assertValueEqual(reserves(t, "a"), 29, "one round transferred");
            h.assertTrue(!buff(t, "chorus_d2:kill_clip", "a"), "refill is not a qualifying reload");
            PugilistGameTest.draw(t, "secondary"); var second = PugilistGameTest.victim(t); PugilistGameTest.impact(t, PugilistGameTest.fire(t), second);
            near(h, state(t).resources().get(new ResourceState.Key(owner(t), ENERGY)).value(), .216, "enhanced shotgun adds .1485 independently");
            var random = state(t).random(); use(t); h.assertValueEqual(mag(t, "b"), 5, "secondary refills without overflow"); h.assertValueEqual(reserves(t, "b"), 29, "secondary consumes reserves");
            h.assertTrue(state(t).random().equals(random), "Clown Cartridge was not sampled");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:demolitionist_cooldown", maxTicks = 85)
    public void serverTicksEnforceCooldownAndAlreadyFullManualReloadDoesNotFinishAsReload(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "demolitionist", DemolitionistGameTest::prepare, true);
        try {
            equip(t); select(t, "free_grenade");
            var target = PugilistGameTest.victim(t); PugilistGameTest.impact(t, PugilistGameTest.fire(t), target);
            h.assertValueEqual(h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon reload", t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)), 1, "manual reload accepted");
            use(t); h.assertValueEqual(mag(t, "a"), 5, "ability filled pending reload's weapon");
            h.runAfterDelay(5, () -> { try {
                h.assertTrue(state(t).reloads().isEmpty() && !buff(t, "chorus_d2:kill_clip", "a"), "zero-transfer manual completion must not qualify Kill Clip");
                input(t, "spend", "a", "rounds", 2, Unit.ROUND);
            } catch (Throwable e) { t.close(); throw e; } });
            h.runAfterDelay(59, () -> { try { use(t); h.assertValueEqual(mag(t, "a"), 3, "pre-deadline use cannot refill"); } catch (Throwable e) { t.close(); throw e; } });
            t.finish(65, () -> { use(t); h.assertValueEqual(mag(t, "a"), 5, "expired cooldown permits refill"); h.assertValueEqual(reserves(t, "a"), 27, "three rounds total transferred"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void laterUnknownAbilityWorldOutcomeRetainsPaidEnergyRefillAndCooldown(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "demolitionist", DemolitionistGameTest::prepare, true)) {
            equip(t); select(t, "grenade"); input(t, "spend", "a", "rounds", 2, Unit.ROUND); t.failAfterHealing = true;
            try { use(t); throw new AssertionError("Expected unknown world outcome"); }
            catch (IllegalStateException expected) { h.assertTrue(expected.getMessage().contains("Injected unknown projectile healing outcome"), "unexpected failure: " + expected); }
            h.assertTrue(t.runtime.failure().isPresent(), "unknown ability outcome retained");
            near(h, state(t).resources().get(new ResourceState.Key(owner(t), ENERGY)).value(), 0, "accepted energy cost retained");
            h.assertValueEqual(mag(t, "a"), 5, "refill committed before body"); h.assertValueEqual(reserves(t, "a"), 28, "reserve transfer committed");
            h.assertTrue(buff(t, COOLDOWN, "a"), "refill cooldown committed"); near(h, t.owner.getHealth(), 11, "actual body heal also occurred once");
        }
        h.succeed();
    }
}
