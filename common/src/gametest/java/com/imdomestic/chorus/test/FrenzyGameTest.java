package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;

public class FrenzyGameTest {
    @GameCase public void nativeEnvironmentalDamageWithoutAnActorDoesNotStartCombat(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "frenzy", FrenzyGameTest::prepare, true)) {
            RampageGameTest.equip(t);
            h.assertTrue(t.owner.hurtServer(h.getLevel(), h.getLevel().damageSources().fall(), 2), "native fall damage accepted");
            near(h, t.owner.getHealth(), 8, "environmental damage actually lost HP");
            h.assertTrue(t.runtime.failure().isEmpty() && state(t).buffs().instances().isEmpty(), "missing attacker is safely excluded from combat progress");
        }
        h.succeed();
    }
    static void prepare(JsonObject data) {
        for (String name : List.of("weapon_stats", "frenzy_weapon", "frenzy_test_calibration")) {
            var fragment = ThreadedSpikeGameTest.json(name);
            for (var entry : fragment.entrySet()) {
                if (entry.getKey().equals("version")) continue;
                if (entry.getValue().isJsonArray()) {
                    if (!data.has(entry.getKey())) data.add(entry.getKey(), new JsonArray());
                    entry.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(entry.getKey()).add(v));
                } else data.add(entry.getKey(), entry.getValue());
            }
        }
    }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static boolean active(ProjectileGameTest.Harness t, String weapon) {
        t.runtime.prepare();
        return state(t).buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:frenzy_active") && b.origin().weapon().equals(weapon));
    }
    static void expect(ProjectileGameTest.Harness t, boolean a, boolean b) {
        t.h.assertTrue(active(t, "a") == a && active(t, "b") == b, "normal/enhanced Frenzy activation differs");
    }
    static double shoot(ProjectileGameTest.Harness t, String slot) throws Exception {
        PugilistGameTest.draw(t, slot); var victim = t.cow(2.5, 46, 3.5); var shot = PugilistGameTest.fire(t);
        for (int i = 0; i < 20 && !shot.isRemoved(); i++) shot.tick();
        t.h.assertTrue(shot.isRemoved() && victim.isAlive() && victim.getHealth() < 100, "physical nonlethal weapon damage");
        double loss = 100 - victim.getHealth(); victim.discard(); return loss;
    }
    static void incoming(ProjectileGameTest.Harness t) {
        var attacker = t.cow(6, 40, 3); float before = t.owner.getHealth();
        t.h.assertTrue(t.owner.hurtServer(t.h.getLevel(), t.h.getLevel().damageSources().mobAttack(attacker), 1), "native incoming attack accepted");
        near(t.h, t.owner.getHealth(), before - 1, "actual native incoming HP loss"); attacker.discard();
    }
    @GameCase(environment = "chorus_gametest:frenzy_combat", maxTicks = 535)
    public void nonlethalCombatActivatesStowedWeaponAndReloadKeepsAcceptedDurationAfterBuffExpiry(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "frenzy", FrenzyGameTest::prepare, true);
        try {
            RampageGameTest.equip(t); near(h, shoot(t, "primary"), 10, "initial unbuffed shot");
            RampageGameTest.at(t, 80, () -> incoming(t)); RampageGameTest.at(t, 160, () -> shoot(t, "secondary"));
            RampageGameTest.at(t, 241, () -> {
                expect(t, true, true); h.assertTrue(t.receipts.stream().noneMatch(r -> r.lethal()), "activation requires no kills");
                var id = t.owner.getUUID().toString(); var query = new EffectEvent(id, id, new BuffInstance.Origin(id, "query", "a", ""), Set.of(), Map.of());
                near(h, t.runtime.program().calculate(state(t), id, query, "chorus_d2:weapon_handling", new Measure(10, Unit.STAT_POINT), List.of()).output().value(), 100, "stowed weapon receives capped handling");
            });
            RampageGameTest.at(t, 250, () -> near(h, shoot(t, "primary"), 11.5, "activated weapon deals actual 15 percent extra damage"));
            RampageGameTest.at(t, 360, () -> incoming(t));
            RampageGameTest.at(t, 490, () -> { t.runtime.reload(t.owner); near(h, state(t).reloads().get(t.owner.getUUID().toString()).duration().value(), 1, "Frenzy stat entered shared reload curve"); });
            RampageGameTest.at(t, 502, () -> { expect(t, false, true); h.assertTrue(!state(t).reloads().isEmpty(), "accepted reload survives the normal buff's expiry"); });
            RampageGameTest.at(t, 513, () -> {
                h.assertValueEqual(state(t).ammunition().get("a").magazine(), 5, "actual refill uses accepted one second");
                h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(), 27, "three rounds transferred from reserves");
            });
            t.finish(522, () -> expect(t, false, false));
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:frenzy_gap", maxTicks = 365)
    public void actualGapResetsNormalProgressWhileEnhancedWindowContinuesAndRefreshUsesCalibration(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "frenzy", FrenzyGameTest::prepare, true);
        try {
            RampageGameTest.equip(t); shoot(t, "secondary");
            RampageGameTest.at(t, 105, () -> incoming(t)); RampageGameTest.at(t, 200, () -> incoming(t));
            RampageGameTest.at(t, 241, () -> expect(t, false, true)); RampageGameTest.at(t, 280, () -> incoming(t));
            t.finish(350, () -> expect(t, true, true));
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:frenzy_detach", maxTicks = 415)
    public void returningAnActualItemAndReequippingItCannotInheritItsCancelledWindup(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "frenzy", FrenzyGameTest::prepare, true);
        try {
            RampageGameTest.equip(t); shoot(t, "primary"); RampageGameTest.at(t, 80, () -> incoming(t));
            RampageGameTest.at(t, 100, () -> {
                var e = PlayerEquipment.get(t.owner); t.owner.getInventory().setItem(0, ItemStack.EMPTY); e.swap(t.owner, "test:primary", 0, e.revision());
                h.assertTrue(!t.owner.getInventory().getItem(0).isEmpty(), "physical item returned");
            });
            RampageGameTest.at(t, 120, () -> { var e = PlayerEquipment.get(t.owner); e.swap(t.owner, "test:primary", 0, e.revision()); });
            RampageGameTest.at(t, 160, () -> incoming(t)); RampageGameTest.at(t, 241, () -> { expect(t, false, true); incoming(t); });
            RampageGameTest.at(t, 320, () -> incoming(t));
            t.finish(402, () -> expect(t, true, true));
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
