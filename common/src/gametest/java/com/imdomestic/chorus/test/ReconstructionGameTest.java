package com.imdomestic.chorus.test;

import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;

/** Actual server time, item sockets, low-permission firing/reloading, conserved magazine transfers. */
public class ReconstructionGameTest {
    static void prepare(JsonObject data) {
        for (var e : ThreadedSpikeGameTest.json("reconstruction_weapon").entrySet()) {
            if (e.getKey().equals("version")) continue;
            if (e.getValue().isJsonArray()) {
                if (!data.has(e.getKey())) data.add(e.getKey(), new JsonArray());
                e.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(e.getKey()).add(v));
            } else data.add(e.getKey(), e.getValue());
        }
    }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception { return open(h, 5); }
    static ProjectileGameTest.Harness open(GameTestHelper h, int magazine) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "reconstruction", data -> {
            prepare(data); data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition").addProperty("magazine", magazine);
        }, true);
        RampageGameTest.equip(t); return t;
    }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static int magazine(ProjectileGameTest.Harness t, String weapon) { return state(t).ammunition().get(weapon).magazine(); }
    static void counts(ProjectileGameTest.Harness t, int a, int b) {
        t.runtime.prepare(); t.h.assertTrue(t.runtime.failure().isEmpty(), "Reconstruction runtime failed: " + t.runtime.failure());
        t.h.assertValueEqual(magazine(t, "a"), a, "normal magazine"); t.h.assertValueEqual(magazine(t, "b"), b, "enhanced magazine");
    }
    static long cues(ProjectileGameTest.Harness t, String cue, String weapon) { return t.cues.stream().filter(c -> c.cue().equals("test:" + cue) && c.source().weapon().equals(weapon)).count(); }
    static void replace(ProjectileGameTest.Harness t, String slot, String variant) {
        var equipment = PlayerEquipment.get(t.owner); t.owner.getInventory().setItem(0, ItemStack.EMPTY);
        equipment.swap(t.owner, "test:" + slot, 0, equipment.revision());
        if (variant == null) return;
        var stack = t.owner.getInventory().getItem(0); var before = stack.get(ChorusComponents.EQUIPMENT.get());
        stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(before.instance(), before.definition(), Map.of("perk", variant), before.parameters()));
        equipment.swap(t.owner, "test:" + slot, 0, equipment.revision());
    }
    @GameCase(environment = "chorus_gametest:reconstruction_cadence", maxTicks = 390)
    public void realTicksFillNormalAndEnhancedWeaponsWhileStowedUpToDoubleWithConservedReserves(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            RampageGameTest.at(t, 20, () -> PugilistGameTest.draw(t, "secondary"));
            RampageGameTest.at(t, 40, () -> PugilistGameTest.draw(t, "primary"));
            RampageGameTest.at(t, 109, () -> counts(t, 5, 5)); RampageGameTest.at(t, 111, () -> counts(t, 5, 7));
            RampageGameTest.at(t, 121, () -> counts(t, 7, 7)); RampageGameTest.at(t, 221, () -> counts(t, 7, 9));
            RampageGameTest.at(t, 241, () -> counts(t, 9, 9)); RampageGameTest.at(t, 331, () -> counts(t, 9, 10));
            t.finish(365, () -> {
                counts(t, 10, 10);
                for (String weapon : List.of("a", "b")) {
                    h.assertValueEqual(state(t).ammunition().get(weapon).reserve().orElseThrow().rounds(), 25, "five rounds transferred, not generated");
                    h.assertValueEqual(t.runtime.program().ammoCapacity(state(t), weapon).capacity(), 5, "overflow is not base capacity");
                    h.assertValueEqual(cues(t, "refilled", weapon), 3L, "three actual transfers"); h.assertValueEqual(cues(t, "reloaded", weapon), 0L, "refill cannot trigger reload perks");
                }
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:reconstruction_fire", maxTicks = 370)
    public void realAcceptedFireRestartsOnlyItsWeaponClockAndCooldownRejectionDoesNot(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            RampageGameTest.kill(t, "primary"); counts(t, 4, 5);
            RampageGameTest.at(t, 100, () -> { RampageGameTest.kill(t, "primary"); counts(t, 3, 5); });
            RampageGameTest.at(t, 102, () -> h.assertValueEqual(t.runtime.fire(t.owner).outcome(), WeaponFire.Outcome.COOLDOWN, "actual early trigger rejected"));
            RampageGameTest.at(t, 121, () -> counts(t, 3, 7)); RampageGameTest.at(t, 219, () -> counts(t, 3, 7));
            RampageGameTest.at(t, 221, () -> counts(t, 5, 9));
            RampageGameTest.at(t, 230, () -> { RampageGameTest.kill(t, "secondary"); counts(t, 5, 8); });
            RampageGameTest.at(t, 339, () -> counts(t, 5, 8));
            t.finish(345, () -> {
                counts(t, 7, 10); h.assertValueEqual(t.projectiles.size(), 3, "three accepted physical shots"); h.assertValueEqual(t.hits.size(), 3, "three actual damage receipts");
                h.assertValueEqual(cues(t, "reloaded", "a"), 0L, "no fabricated manual reload");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:reconstruction_reload", maxTicks = 150)
    public void actualManualReloadSpendsReservesButDoesNotPostponeTheStoppedFireTimer(GameTestHelper h) throws Exception {
        var t = open(h, 1);
        try {
            RampageGameTest.kill(t, "primary"); counts(t, 0, 1);
            RampageGameTest.at(t, 80, () -> PugilistGameTest.draw(t, "primary"));
            RampageGameTest.at(t, 80, () -> h.assertValueEqual(h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon reload", t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS)), 1, "real manual reload input"));
            RampageGameTest.at(t, 90, () -> { counts(t, 5, 1); h.assertValueEqual(cues(t, "reloaded", "a"), 1L, "manual reload emits completion"); });
            t.finish(125, () -> {
                counts(t, 7, 3); h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(), 23, "five manual plus two automatic rounds");
                h.assertValueEqual(cues(t, "refilled", "a"), 2L, "both actual transfers observed"); h.assertValueEqual(cues(t, "reloaded", "a"), 1L, "automatic refill adds no reload fact");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:reconstruction_replace", maxTicks = 370)
    public void actualSocketReplacementCancelsOldCadenceAndRemovalRetainsLoadedAmmo(GameTestHelper h) throws Exception {
        var t = open(h); final EffectState[] removed = new EffectState[1];
        try {
            RampageGameTest.at(t, 100, () -> replace(t, "primary", "enhanced"));
            RampageGameTest.at(t, 121, () -> counts(t, 5, 7)); RampageGameTest.at(t, 209, () -> counts(t, 5, 7));
            RampageGameTest.at(t, 211, () -> { counts(t, 7, 7); h.assertValueEqual(state(t).timers().size(), 2, "one timer per perk source"); });
            RampageGameTest.at(t, 225, () -> { replace(t, "primary", null); replace(t, "secondary", null); removed[0] = state(t); h.assertTrue(state(t).timers().isEmpty(), "removing weapons cancels source timers"); });
            t.finish(345, () -> { h.assertValueEqual(state(t).ammunition(), removed[0].ammunition(), "removed weapon retains balances without further refill"); h.assertTrue(state(t).timers().isEmpty(), "old timer resurrected"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:reconstruction_unknown", maxTicks = 280)
    public void unknownAutomaticRefillReactionRetainsOneTransferWithoutReplaying(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            replace(t, "secondary", null); t.failAfterCue = true;
            RampageGameTest.at(t, 125, () -> {
                h.assertTrue(t.runtime.failure().isPresent(), "post-refill world result must be unknown");
                h.assertValueEqual(magazine(t, "a"), 7, "committed transfer retained"); h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(), 28, "committed reserve debit retained");
            });
            RampageGameTest.at(t, 245, () -> {
                try {
                    h.assertValueEqual(magazine(t, "a"), 7, "failed runtime cannot repeat later pulse"); h.assertValueEqual(t.cues.size(), 1, "unknown cue not replayed");
                    boolean rejected = false; try { t.runtime.fire(t.owner); } catch (IllegalStateException expected) { rejected = true; }
                    h.assertTrue(rejected, "failed runtime rejects new input"); h.succeed();
                } finally { t.close(); }
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
