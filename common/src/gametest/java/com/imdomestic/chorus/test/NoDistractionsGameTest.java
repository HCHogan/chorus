package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.motion.AimImpulse;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

public class NoDistractionsGameTest {
    static final String D = "chorus_d2:", B = D + "no_distractions";
    static void put(ProjectileGameTest.Harness t, String slot, Loadout.Gear gear) {
        var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), gear);
        t.owner.getInventory().setItem(0, stack); var equipment = PlayerEquipment.get(t.owner); equipment.swap(t.owner, "test:" + slot, 0, equipment.revision());
        t.runtime.prepare();
    }
    static void gun(ProjectileGameTest.Harness t, String slot, String id, String variant) {
        put(t, slot, new Loadout.Gear(id, "test:rifle", Map.of("perk", variant), Map.of("stability", new Measure(50, Unit.STAT_POINT), "enhanced_delay", new Measure(.9, Unit.SECOND))));
    }
    static void helmet(ProjectileGameTest.Harness t, double health) {
        put(t, "helmet", new Loadout.Gear("helmet-" + health, "test:helmet", Map.of(), Map.of("health", new Measure(health, Unit.STAT_POINT))));
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, boolean enhanced) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "no_distractions_weapon", data -> RadiantGameTest.merge(data, "no_distractions", "weapon_flinch", "weapon_stats", "character_stats"), true);
        try {
            t.owner.setXRot(0); t.owner.setYRot(0); t.owner.setGameMode(GameType.SURVIVAL); t.owner.setInvulnerableTime(0); t.owner.getFoodData().setFoodLevel(0);
            gun(t, "primary", "a", enhanced ? "enhanced" : "normal"); gun(t, "secondary", "b", "enhanced"); helmet(t, 100); PugilistGameTest.draw(t, "primary");
            var attacker = t.cow(10, 40, 10); String enemy = attacker.getUUID().toString();
            t.runtime.bind(new EffectSource("attack", "test:hit", enemy, new BuffInstance.Origin(enemy, "attack", "enemy_gun", ""), Set.of()));
            return t;
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    static boolean active(ProjectileGameTest.Harness t) {
        return t.runtime.state().engine().domain().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals(B));
    }
    static void hit(ProjectileGameTest.Harness t) {
        t.owner.setYRot(0); t.owner.setXRot(0);
        var attacker = t.runtime.state().engine().domain().sources().get("attack").origin();
        t.runtime.start(new RuleEngine.Signal("test:hit", new EffectEvent(attacker.owner(), t.owner.getUUID().toString(), attacker, Set.of(), Map.of())));
    }
    static void angle(ProjectileGameTest.Harness t, double factor) {
        near(t.h, t.owner.getXRot(), -10 * factor, "real pitch"); near(t.h, t.owner.getYRot(), 2 * factor, "real yaw");
        t.h.assertTrue(t.runtime.failure().isEmpty(), "flinch runtime failed: " + t.runtime.failure());
    }
    static void variant(GameTestHelper h, boolean enhanced) throws Exception {
        var t = open(h, enhanced);
        try {
            double initial = t.owner.getHealth(); hit(t); angle(t, .90625); near(h, t.owner.getHealth(), initial - 1, "real health loss");
            WeaponAimGameTest.input(t, true); int deadline = enhanced ? 18 : 20;
            at(t, 4, () -> { hit(t); angle(t, .90625 * .9); h.assertTrue(!active(t), "No Distractions activated early"); });
            at(t, deadline - 1, () -> h.assertTrue(!active(t), "windup shortened"));
            at(t, deadline, () -> { try (t) {
                t.runtime.prepare(); h.assertTrue(active(t), "ADS input deadline missed"); hit(t); angle(t, .90625 * .9 * .65);
                t.owner.setYRot(0); t.owner.setXRot(0); var target = t.cow(2, 40, 8);
                target.setPos(t.owner.getX(), t.owner.getEyeY() - target.getBbHeight() / 2, t.owner.getZ() + 5);
                h.assertValueEqual(t.runtime.fire(t.owner).outcome(), WeaponFire.Outcome.ACCEPTED, "actual held gun fired");
                near(h, target.getHealth(), 99, "real hitscan damaged target"); h.assertTrue(active(t), "firing consumed No Distractions");
                h.assertValueEqual(PrecisionAmmoGameTest.magazine(t, "a"), 11, "one round paid");
                hit(t); angle(t, .90625 * .9 * .65); WeaponAimGameTest.input(t, false);
                h.assertTrue(!active(t), "ADS release retained perk"); hit(t); angle(t, .90625); h.succeed();
            }});
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:no_distractions_normal", maxTicks=40)
    public void normalVariantChangesRealDamageFlinchAndSurvivesActualWeaponFire(GameTestHelper h) throws Exception { variant(h, false); }
    @GameCase(environment="chorus_gametest:no_distractions_enhanced", maxTicks=40)
    public void enhancedVariantUsesExplicitCalibrationAndSurvivesActualWeaponFire(GameTestHelper h) throws Exception { variant(h, true); }
    @GameCase(environment="chorus_gametest:no_distractions_rearm", maxTicks=50)
    public void acceptedEarlyFireRestartsTheWaitButRejectedFireDoesNot(GameTestHelper h) throws Exception {
        var t = open(h, false); WeaponAimGameTest.input(t, true);
        at(t, 10, () -> h.assertValueEqual(t.runtime.fire(t.owner).outcome(), WeaponFire.Outcome.ACCEPTED, "early paid shot"));
        at(t, 11, () -> h.assertValueEqual(t.runtime.fire(t.owner).outcome(), WeaponFire.Outcome.COOLDOWN, "early rejected shot"));
        at(t, 29, () -> { t.runtime.prepare(); h.assertTrue(!active(t), "not a full quiet second"); });
        at(t, 30, () -> { try (t) { t.runtime.prepare(); h.assertTrue(active(t), "quiet timer failed"); hit(t); angle(t, .90625 * .9 * .65); h.succeed(); }});
    }
    @GameCase(environment="chorus_gametest:no_distractions_equipment", maxTicks=65)
    public void armorAndHeldWeaponChangesResampleResistanceWithoutBorrowingStowedPerks(GameTestHelper h) throws Exception {
        var t = open(h, false); WeaponAimGameTest.input(t, true);
        at(t, 20, () -> {
            t.runtime.prepare(); h.assertTrue(active(t), "initial active perk"); helmet(t, 0); h.assertTrue(active(t), "unrelated armor restarted perk");
            String owner = t.owner.getUUID().toString();
            t.runtime.bind(new EffectSource("bonus", "test:stability_bonus", owner, new BuffInstance.Origin(owner, "bonus", "a", ""), Set.of(), Map.of("points", new Measure(100, Unit.STAT_POINT))));
            hit(t); angle(t, .75 * .65); PugilistGameTest.draw(t, "secondary"); h.assertTrue(!active(t), "stow retained perk"); hit(t); angle(t, .90625);
            WeaponAimGameTest.input(t, true);
            at(t, 9, () -> { gun(t, "secondary", "b2", "none"); h.assertTrue(!active(t), "removing perk granted benefit"); });
            at(t, 20, () -> { try (t) { t.runtime.prepare(); h.assertTrue(!active(t), "old enhanced timer survived detach"); hit(t); angle(t, .90625); h.succeed(); }});
        });
    }
    @GameCase(environment="chorus_gametest:no_distractions_absorption")
    public void absorptionLossFlinchesWhileUndeclaredNativeDamageDoesNotInventAngles(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            t.owner.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.ABSORPTION, 600, 0));
            t.owner.setAbsorptionAmount(2); double hp = t.owner.getHealth(); hit(t); angle(t, .90625);
            near(h, t.owner.getHealth(), hp, "absorption protected health"); near(h, t.owner.getAbsorptionAmount(), 1, "actual absorption loss");
            t.owner.setXRot(0); t.owner.setYRot(0); t.owner.damageCooldownTime = 0;
            h.assertTrue(t.owner.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1), "untagged native damage fixture");
            near(h, t.owner.getAbsorptionAmount(), 0, "untagged damage consumed remaining absorption");
            near(h, t.owner.getHealth(), hp, "untagged damage remained fully absorbed");
            near(h, t.owner.getXRot(), 0, "undeclared pitch not invented"); near(h, t.owner.getYRot(), 0, "undeclared yaw not invented");
            h.assertTrue(t.runtime.failure().isEmpty(), "untagged damage evaluated missing angle fields"); h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:no_distractions_unknown")
    public void unknownFlinchRetainsPriorDamageAndRotationWithoutReplayingEither(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            int[] writes = {0}; t.beforeWorld = request -> {
                if (request.command() instanceof AimImpulse.Command) { writes[0]++; t.world.apply(request); throw new IllegalStateException("Unknown applied flinch"); }
            };
            boolean failed = false; try { hit(t); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent(), "unknown flinch did not stop runtime");
            near(h, t.owner.getHealth(), 9, "earlier damage retained"); near(h, t.owner.getXRot(), -9.0625, "native aim write retained");
            h.assertTrue(t.cues.isEmpty(), "later flinch cue ran");
            var attacker = t.runtime.state().engine().domain().sources().get("attack").origin();
            boolean rejected = false; try { t.runtime.start(new RuleEngine.Signal("test:hit", new EffectEvent(attacker.owner(), t.owner.getUUID().toString(), attacker, Set.of(), Map.of()))); } catch (IllegalStateException expected) { rejected = true; }
            h.assertTrue(rejected, "failed world write was retried"); h.assertValueEqual(writes[0], 1, "single angle write"); h.assertValueEqual(t.hits.size(), 1, "single damage write"); h.succeed();
        }
    }
}
