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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;

public class PugilistGameTest {
    static void prepare(JsonObject data) {
        EnergyGainGameTest.prepare(data);
        for (String fixture : List.of("pugilist", "ability_energy_targets", "pugilist_weapon")) {
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
    static String owner(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static void equip(ProjectileGameTest.Harness t) {
        t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var equipment = PlayerEquipment.get(t.owner);
        for (String id : List.of("a", "b")) {
            var stack = new ItemStack(Items.DIAMOND_SWORD);
            stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, id.equals("a") ? "test:rifle" : "test:shotgun", Map.of("perk", id.equals("a") ? "normal" : "enhanced")));
            t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
        }
        draw(t, "primary");
        var origin = new BuffInstance.Origin(owner(t), "melee", "", "test:melee");
        t.runtime.bind(new EffectSource("melee", "test:pugilist_melee", owner(t), origin, Set.of()));
    }
    static void draw(ProjectileGameTest.Harness t, String slot) { var e = PlayerEquipment.get(t.owner); e.draw(t.owner, Optional.of("test:" + slot), e.revision()); }
    static void select(ProjectileGameTest.Harness t, String ability) {
        t.runtime.abilities(new AbilityChange(owner(t), t.runtime.state().engine().domain().abilities().getOrDefault(owner(t), AbilityLoadout.EMPTY),
                ability == null ? AbilityLoadout.EMPTY : new AbilityLoadout(Map.of("chorus_d2:melee", ability))));
    }
    static double energy(ProjectileGameTest.Harness t, String resource) { return t.runtime.state().engine().domain().resources().get(new ResourceState.Key(owner(t), resource)).value(); }
    static LivingEntity victim(ProjectileGameTest.Harness t) { var e = t.cow(2.5, 46, 3.5); e.setHealth(1); return e; }
    static EffectProjectile fire(ProjectileGameTest.Harness t) throws Exception {
        t.h.assertValueEqual(t.h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon fire", t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)), 1, "actual equipped weapon input");
        return t.projectiles.getLast();
    }
    static void impact(ProjectileGameTest.Harness t, EffectProjectile projectile, LivingEntity target) {
        for (int i = 0; i < 20 && !projectile.isRemoved(); i++) projectile.tick();
        t.h.assertTrue(projectile.isRemoved() && target.isDeadOrDying(), "physical projectile confirms the kill"); target.discard();
        t.h.assertTrue(t.runtime.failure().isEmpty(), "healthy perk execution: " + t.runtime.failure());
    }
    static void melee(ProjectileGameTest.Harness t, LivingEntity target) {
        t.runtime.start(new RuleEngine.Signal("test:melee", new EffectEvent(owner(t), target.getUUID().toString(), new BuffInstance.Origin(owner(t), "melee", "", "test:melee"), Set.of(), Map.of())));
    }
    static double handling(ProjectileGameTest.Harness t, String weapon) {
        var origin = new BuffInstance.Origin(owner(t), "query", weapon, "");
        return t.runtime.program().calculate(t.runtime.state().engine().domain(), owner(t), new EffectEvent(owner(t), owner(t), origin, Set.of(), Map.of()),
                "chorus_d2:weapon_handling", new Measure(10, Unit.STAT_POINT), List.of()).output().value();
    }
    @GameCase public void equippedWeaponKillsCreditCurrentMeleeSelectionEvenWhenItChangesInFlight(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "threaded_spike", PugilistGameTest::prepare, true)) {
            EnergyGainGameTest.start(t); equip(t); EnergyGainGameTest.stat(t, 100);
            var first = victim(t); impact(t, fire(t), first);
            near(h, energy(t, "chorus_d2:threaded_spike_energy"), .072, "normal rifle uses .04 base, 100 stat and .8 CES once");
            draw(t, "secondary"); var second = victim(t); var shot = fire(t); select(t, "test:alternate"); impact(t, shot, second);
            near(h, energy(t, "test:alternate_energy"), .044, "enhanced shotgun .088 base uses the newly selected synthetic .5 recipient");
            near(h, energy(t, "chorus_d2:threaded_spike_energy"), .072, "previous ability account unchanged by the second kill");
            h.assertValueEqual(t.runtime.state().engine().domain().ammunition().get("a").magazine(), 4, "actual first weapon paid ammo");
            h.assertValueEqual(t.runtime.state().engine().domain().ammunition().get("b").magazine(), 4, "actual second weapon paid ammo");
        }
        h.succeed();
    }
    @GameCase public void uncreditedKillsAndAbsentMeleeSelectionDoNotCreateEnergyOrFailTheRuntime(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "threaded_spike", data -> {
            prepare(data);
            data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action").add("kill_tags", new JsonArray());
        }, true)) {
            EnergyGainGameTest.start(t); equip(t); var first = victim(t); impact(t, fire(t), first);
            near(h, energy(t, "chorus_d2:threaded_spike_energy"), 0, "real kill lacks weapon kill credit");
            select(t, null); draw(t, "secondary"); var second = victim(t); impact(t, fire(t), second);
            near(h, energy(t, "chorus_d2:threaded_spike_energy"), 0, "no selected melee is an explicit no grant");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:pugilist_handling", maxTicks = 95)
    public void actualMeleeDamageRefreshesThreeSecondHandlingAndImmuneContactDoesNot(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "threaded_spike", PugilistGameTest::prepare, true);
        try {
            EnergyGainGameTest.start(t); equip(t);
            var immune = t.cow(6, 40, 3); immune.setPermanentlyInvulnerable(true); melee(t, immune);
            h.assertValueEqual(t.receipts.getLast().outcome(), com.imdomestic.chorus.effect.combat.DamageReceipt.Outcome.IMMUNE, "server damage was actually rejected");
            near(h, handling(t, "a"), 10, "immune contact gives no handling");
            var target = t.cow(7, 40, 3); melee(t, target); near(h, target.getHealth(), 99, "real melee loses health");
            near(h, handling(t, "a"), 45, "held weapon gains handling"); near(h, handling(t, "b"), 45, "other equipped instance also gains handling");
            draw(t, "secondary");
            h.runAfterDelay(20, () -> { try { melee(t, t.cow(8, 40, 3)); } catch (Throwable e) { t.close(); throw e; } });
            h.runAfterDelay(65, () -> { try { near(h, handling(t, "a"), 45, "refresh outlasts first grant and stow keeps timer"); } catch (Throwable e) { t.close(); throw e; } });
            t.finish(85, () -> { near(h, handling(t, "a"), 10, "refreshed duration expires"); near(h, handling(t, "b"), 10, "both instances expire"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
