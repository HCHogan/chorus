package com.imdomestic.chorus.test;

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

/** Paid synthetic super lifecycle, real weapon containers, projectiles, reloads and private ammo bricks. */
public class TimelostMagazineGameTest {
    static String owner(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static void prepare(JsonObject data) {
        for (String name : List.of("timelost_weapon", "timelost_test_calibration", "reload_insert_rounds", "overflow", "ammo_pickups")) for (var e : ThreadedSpikeGameTest.json(name).entrySet()) {
            if (e.getKey().equals("version")) continue;
            if (e.getValue().isJsonArray()) { if (!data.has(e.getKey())) data.add(e.getKey(), new JsonArray()); e.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(e.getKey()).add(v)); }
            else data.add(e.getKey(), e.getValue());
        }
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, boolean found, String overflow) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "timelost_magazine", TimelostMagazineGameTest::prepare, true);
        try {
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX() + 2.5, t.owner.getY(), t.owner.chunkPosition().getMinBlockZ() + 6.5);
            t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); var equipment = PlayerEquipment.get(t.owner);
            for (String id : List.of("a", "b")) {
                var parameters = Map.of("base_super_gain", new Measure(.01, Unit.CHARGE), "enhanced_super_gain", new Measure(.02, Unit.CHARGE));
                var stack = new ItemStack(Items.DIAMOND_SWORD); String perk = id.equals("a") ? (found ? "found" : "normal") : (found ? "enhanced_found" : "enhanced");
                stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", perk, "overflow", id.equals("a") ? overflow : "none"), parameters));
                t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            PugilistGameTest.draw(t, "primary"); select(t, "test:super"); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static void select(ProjectileGameTest.Harness t, String ability) {
        t.runtime.abilities(new AbilityChange(owner(t), state(t).abilities().getOrDefault(owner(t), AbilityLoadout.EMPTY), ability == null ? AbilityLoadout.EMPTY : new AbilityLoadout(Map.of("chorus_d2:super", ability))));
    }
    static void command(ProjectileGameTest.Harness t, String command) throws Exception {
        t.h.assertValueEqual(t.h.getLevel().getServer().getCommands().getDispatcher().execute(command, t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)), 1, "player command accepted");
    }
    static void cast(ProjectileGameTest.Harness t) throws Exception { command(t, "chorus ability use chorus_d2:super"); }
    static int capacity(ProjectileGameTest.Harness t, String weapon) { return t.runtime.program().ammoCapacity(state(t), weapon).capacity(); }
    static int mag(ProjectileGameTest.Harness t, String weapon) { return state(t).ammunition().get(weapon).magazine(); }
    static int reserve(ProjectileGameTest.Harness t, String weapon) { return state(t).ammunition().get(weapon).reserve().orElseThrow().rounds(); }
    static double energy(ProjectileGameTest.Harness t, String resource) { return state(t).resources().get(new ResourceState.Key(owner(t), resource)).value(); }
    static long cues(ProjectileGameTest.Harness t, String cue) { return t.cues.stream().filter(c -> c.cue().equals("test:" + cue)).count(); }
    static void kill(ProjectileGameTest.Harness t) throws Exception { PugilistGameTest.impact(t, PugilistGameTest.fire(t), ChainReactionGameTest.target(t, 0, 1)); }
    @GameCase(environment = "chorus_gametest:timelost_lifecycle", maxTicks = 450)
    public void paidSuperEndsOnceAndStowedNormalEnhancedWindowsExpireWithoutDeletingLoadedRounds(GameTestHelper h) throws Exception {
        var t = open(h, false, "none");
        try {
            kill(t); cast(t); HealingGameTest.near(h, energy(t, "test:super_energy"), 0, "super actually paid"); h.assertValueEqual(capacity(t, "a"), 5, "no early expansion");
            RampageGameTest.at(t, 8, () -> {
                h.assertValueEqual(capacity(t, "a"), 10, "normal expanded"); h.assertValueEqual(capacity(t, "b"), 10, "stowed enhanced expanded"); h.assertValueEqual(mag(t, "a"), 10, "normal refill"); h.assertValueEqual(mag(t, "b"), 10, "stowed refill");
                h.assertValueEqual(reserve(t, "a"), 24, "spent shot and refill conserve ammo"); h.assertValueEqual(reserve(t, "b"), 25, "stowed own reserves"); h.assertValueEqual(cues(t, "super_ended"), 1L, "one actual expiry"); h.assertValueEqual(cues(t, "reloaded"), 0L, "no manual reload fact"); PugilistGameTest.draw(t, "secondary");
            });
            RampageGameTest.at(t, 410, () -> { h.assertValueEqual(capacity(t, "a"), 5, "normal twenty seconds expired"); h.assertValueEqual(capacity(t, "b"), 10, "enhanced twenty one seconds active"); h.assertValueEqual(mag(t, "a"), 10, "stowed loaded rounds preserved"); });
            t.finish(430, () -> { h.assertValueEqual(capacity(t, "b"), 5, "enhanced expired"); h.assertValueEqual(mag(t, "b"), 10, "enhanced loaded rounds preserved"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:timelost_shells", maxTicks = 55)
    public void foundVerdictUsesTwoActualShellTransfersAfterFourPaidShots(GameTestHelper h) throws Exception {
        var t = open(h, true, "none");
        try {
            cast(t); for (int tick : List.of(6, 10, 14, 18)) RampageGameTest.at(t, tick, () -> kill(t));
            RampageGameTest.at(t, 19, () -> { h.assertValueEqual(mag(t, "a"), 6, "four physical shots paid"); command(t, "chorus weapon reload"); h.assertValueEqual(state(t).reloads().get(owner(t)).portion().orElseThrow().rounds(), 2, "two shells accepted"); });
            RampageGameTest.at(t, 24, () -> h.assertValueEqual(mag(t, "a"), 8, "first insertion actually transferred two"));
            t.finish(30, () -> { h.assertValueEqual(mag(t, "a"), 10, "second insertion completed"); h.assertValueEqual(reserve(t, "a"), 21, "four rounds from own reserves"); h.assertValueEqual(cues(t, "reloaded"), 2L, "one completion fact per insertion"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:timelost_energy", maxTicks = 45)
    public void physicalWeaponKillsCreditTheSelectedSuperAtImpactUsingOnlyTheirOwnPerk(GameTestHelper h) throws Exception {
        var t = open(h, false, "none");
        try {
            cast(t); RampageGameTest.at(t, 6, () -> { kill(t); HealingGameTest.near(h, energy(t, "test:super_energy"), .01, "normal configured bonus"); });
            RampageGameTest.at(t, 10, () -> {
                PugilistGameTest.draw(t, "secondary"); var victim = ChainReactionGameTest.target(t, 0, 1); var shot = PugilistGameTest.fire(t); select(t, "test:alternate_super"); PugilistGameTest.impact(t, shot, victim);
                HealingGameTest.near(h, energy(t, "test:alternate_super_energy"), .02, "enhanced bonus reaches current selected account"); HealingGameTest.near(h, energy(t, "test:super_energy"), .01, "previous account unchanged");
            });
            RampageGameTest.at(t, 14, () -> { select(t, null); kill(t); HealingGameTest.near(h, energy(t, "test:alternate_super_energy"), .02, "missing selection produces no phantom account grant"); });
            t.finish(20, () -> h.assertValueEqual(t.receipts.stream().filter(r -> r.lethal()).count(), 3L, "three real kills"));
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:timelost_overflow", maxTicks = 45)
    public void realAmmoBrickUsesExpandedBaseForEnhancedOverflowAndConservesReserves(GameTestHelper h) throws Exception {
        var t = open(h, false, "enhanced");
        try {
            t.runtime.bind(new EffectSource("factory", "chorus_d2:ammo_pickups", owner(t), new BuffInstance.Origin(owner(t), "drop", "", ""), Set.of(), Map.of("lifetime", new Measure(10, Unit.SECOND), "radius", new Measure(.5, Unit.METER))));
            cast(t); RampageGameTest.at(t, 6, () -> OverflowGameTest.spawn(t, "special", t.owner));
            t.finish(12, () -> { h.assertTrue(t.pickups.getLast().isRemoved(), "private brick actually collected"); h.assertValueEqual(capacity(t, "a"), 10, "base remains doubled"); h.assertValueEqual(mag(t, "a"), 22, "enhanced Overflow uses ten round baseline");
                h.assertValueEqual(reserve(t, "a"), 14, "one collected, twelve moved to magazine"); h.assertValueEqual(reserve(t, "b"), 26, "other weapon received only its one round"); h.assertValueEqual(cues(t, "reloaded"), 0L, "no fabricated manual reload"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:timelost_detach", maxTicks = 40)
    public void replacingPerkBeforeExpiryPreventsActivationAndLaterDetachPreservesLoadedAmmo(GameTestHelper h) throws Exception {
        var t = open(h, false, "none");
        try {
            cast(t); ReconstructionGameTest.replace(t, "primary", "none");
            t.finish(8, () -> { h.assertValueEqual(capacity(t, "a"), 5, "removed source cannot activate"); h.assertValueEqual(mag(t, "a"), 5, "no lost perk refill"); h.assertValueEqual(mag(t, "b"), 10, "remaining stowed source activates");
                ReconstructionGameTest.replace(t, "secondary", "none"); h.assertValueEqual(capacity(t, "b"), 5, "active capacity removed"); h.assertValueEqual(mag(t, "b"), 10, "loaded rounds retained"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:timelost_cancel", maxTicks = 40)
    public void removingSyntheticSuperStateDoesNotForgeAnExpiryReward(GameTestHelper h) throws Exception {
        var t = open(h, false, "none");
        try {
            cast(t); t.runtime.start(new RuleEngine.Signal("test:cancel_super", new EffectEvent(owner(t), owner(t), new BuffInstance.Origin(owner(t), "control", "", ""), Set.of(), Map.of())));
            t.finish(8, () -> { h.assertValueEqual(cues(t, "super_ended"), 0L, "removal is distinct from expiry"); h.assertValueEqual(mag(t, "a"), 5, "no removal refill"); h.assertValueEqual(capacity(t, "b"), 5, "no removal expansion"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:timelost_unknown", maxTicks = 40)
    public void unknownWorldReactionAfterRefillDoesNotDuplicateTheCommittedTransfer(GameTestHelper h) throws Exception {
        var t = open(h, false, "none");
        try {
            t.onCue = c -> { if (c.cue().equals("test:refilled")) throw new IllegalStateException("unknown committed Timelost refill reaction"); }; cast(t);
            h.runAfterDelay(8, () -> { try (t) {
                h.assertTrue(t.runtime.failure().isPresent(), "missing world reaction failure"); h.assertValueEqual(mag(t, "a"), 10, "committed refill remains"); h.assertValueEqual(reserve(t, "a"), 25, "reserve transfer occurred once");
                MinecraftEffectRuntime.tick(h.getLevel()); h.assertValueEqual(reserve(t, "a"), 25, "no replay"); h.succeed();
            } });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
