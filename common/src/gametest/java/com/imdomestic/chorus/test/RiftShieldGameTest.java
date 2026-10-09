package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

public class RiftShieldGameTest {
    private static final String SHIELD = "chorus_d2:rift_overshield";
    private static String id(LivingEntity entity) { return entity.getUUID().toString(); }
    private static Optional<BuffInstance> pool(HealingRiftGameTest.Harness test, String name) {
        return test.runtime.state().engine().domain().buffs().instances().values().stream()
                .filter(b -> b.definition().id().equals(name) && b.key().holder().equals(id(test.ally))).findFirst();
    }
    private static double capacity(HealingRiftGameTest.Harness test, String name) { return pool(test, name).orElseThrow().components().numbers().get("capacity"); }
    private static void hit(HealingRiftGameTest.Harness test, float amount) {
        test.ally.damageCooldownTime = 0; test.ally.hurtServer(test.h.getLevel(), test.h.getLevel().damageSources().generic(), amount); test.settled();
    }
    private static EffectSource helper(HealingRiftGameTest.Harness test) {
        var source = new EffectSource("shield-host", "test:shield_actions", id(test.ally), new BuffInstance.Origin(id(test.ally), "shield-host", "shield-weapon", ""), Set.of());
        test.bind(source); return source;
    }
    private static void command(HealingRiftGameTest.Harness test, EffectSource source, String event) {
        test.runtime.start(new RuleEngine.Signal("test:" + event, new EffectEvent(id(test.ally), id(test.ally), source.origin(), Set.of(), Map.of()))); test.settled();
    }
    @GameCase(environment = "chorus_gametest:rift_shield_damage", maxTicks = 115)
    public void shieldChargesToCapTakesNativeDamageAndResumesOnlyAfterHealthIsFull(GameTestHelper h) throws Exception {
        var test = new HealingRiftGameTest.Harness(h, EffectState.Mode.PVE, true, true);
        try {
            test.ally.setHealth(100); test.bind(test.a); long generation = pool(test, SHIELD).orElseThrow().generation();
            test.at(1, () -> near(h, capacity(test, SHIELD), .015, "first shield pulse"));
            test.at(100, () -> {
                near(h, capacity(test, SHIELD), 1.5, "five seconds at 0.3 projected shield per second");
                hit(test, .5f); near(h, capacity(test, SHIELD), 1, "native damage consumes generated capacity"); near(h, test.ally.getHealth(), 100, "shield protects native health");
            });
            test.at(101, () -> {
                near(h, capacity(test, SHIELD), 1.015, "same layer replenishes after shield-only damage");
                h.assertValueEqual(pool(test, SHIELD).orElseThrow().generation(), generation, "repair cannot rebuild layer or change FIFO age");
                hit(test, 2); near(h, capacity(test, SHIELD), 0, "layer depleted"); near(h, test.ally.getHealth(), 99.015, "actual overflow reaches health");
            });
            test.at(102, () -> {
                near(h, capacity(test, SHIELD), 0, "wounded member cannot generate shield"); near(h, test.ally.getHealth(), 99.215, "continuous health recovery continues");
                test.ally.setHealth(100);
            });
            test.at(103, () -> {
                try (test) {
                    near(h, capacity(test, SHIELD), .015, "full-health observation resumes one pulse without banking");
                    test.dismiss(test.a); h.assertTrue(pool(test, SHIELD).isEmpty(), "leaving final field removes remaining Rift shield"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:rift_shield_overlap", maxTicks = 12)
    public void overlappingPvpFieldsShareGenerationAndLeavingOneDoesNotClearAnother(GameTestHelper h) throws Exception {
        var test = new HealingRiftGameTest.Harness(h, EffectState.Mode.PVP, true, true);
        try {
            test.ally.setHealth(100); test.bind(test.a); test.bind(test.b); long generation = pool(test, SHIELD).orElseThrow().generation();
            test.at(2, () -> { near(h, capacity(test, SHIELD), .03, "overlap must not double shield rate"); test.dismiss(test.a); });
            test.at(3, () -> {
                near(h, capacity(test, SHIELD), .045, "remaining field continues shared pool"); h.assertValueEqual(pool(test, SHIELD).orElseThrow().generation(), generation, "first field exit retains pool");
                test.dismiss(test.b); h.assertTrue(pool(test, SHIELD).isEmpty(), "final exit clears pool");
                test.bind(test.source("cast-c")); near(h, capacity(test, SHIELD), 0, "new visit starts empty");
                h.assertTrue(pool(test, SHIELD).orElseThrow().generation() != generation, "new visit creates a new layer");
            });
            test.at(4, () -> { try (test) { near(h, capacity(test, SHIELD), .015, "PvP reentry pulse"); h.succeed(); } });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:rift_shield_void", maxTicks = 12)
    public void voidShieldBlocksGenerationWhileOtherLayersAndNativeAbsorptionStayIndependent(GameTestHelper h) throws Exception {
        var test = new HealingRiftGameTest.Harness(h, EffectState.Mode.PVE, true, true);
        try {
            test.ally.setHealth(100); test.ally.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 1)); test.ally.setAbsorptionAmount(5);
            var helper = helper(test); command(test, helper, "void"); test.bind(test.a);
            test.at(1, () -> {
                near(h, capacity(test, SHIELD), 0, "positive Void pool blocks generation"); near(h, capacity(test, "test:void"), 1, "Void remains intact"); command(test, helper, "remove_void");
            });
            test.at(2, () -> {
                near(h, capacity(test, SHIELD), .015, "native Absorption is not Void Overshield"); command(test, helper, "void");
            });
            test.at(3, () -> {
                near(h, capacity(test, SHIELD), .015, "new Void layer does not erase existing Rift capacity"); hit(test, .5f);
                near(h, capacity(test, SHIELD), 0, "older Rift layer is consumed first"); near(h, capacity(test, "test:void"), .515, "remaining damage consumes later Void layer");
                near(h, test.ally.getHealth(), 100, "independent shield layers protect health"); near(h, test.ally.getAbsorptionAmount(), 5, "Chorus layers protect native Absorption");
            });
            test.at(4, () -> { near(h, capacity(test, SHIELD), 0, "remaining positive Void still blocks"); command(test, helper, "remove_void"); });
            test.at(5, () -> { try (test) { near(h, capacity(test, SHIELD), .015, "generation resumes after Void ends"); h.succeed(); } });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:rift_shield_qualification", maxTicks = 10)
    public void riftPresenceCountsForPerkQualificationEvenWhenNoShieldCapacityHasBeenGenerated(GameTestHelper h) throws Exception {
        var test = new HealingRiftGameTest.Harness(h, EffectState.Mode.PVE, true, true);
        try {
            var helper = helper(test); test.bind(test.a); command(test, helper, "probe_rift");
            h.assertValueEqual(test.cues.stream().map(c -> c.cue()).toList(), List.of("test:counted"), "presence qualifies while empty pool provides no protection");
            near(h, capacity(test, SHIELD), 0, "wounded member starts with zero shield");
            test.ally.setPos(test.ally.getX(), test.ally.getY() + 16, test.ally.getZ());
            test.at(1, () -> {
                try (test) {
                    test.cues.clear(); command(test, helper, "probe_rift");
                    h.assertTrue(test.cues.isEmpty() && pool(test, SHIELD).isEmpty(), "leaving field clears both interaction qualification and layer"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
}
