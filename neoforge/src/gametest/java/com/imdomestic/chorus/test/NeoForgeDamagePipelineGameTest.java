package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Loader-specific mutation points must preserve the same shield budget and actual-loss contract. */
public class NeoForgeDamagePipelineGameTest {
    private record Shielded(MinecraftEffectRuntime runtime, LivingEntity target, LivingEntity attacker) implements AutoCloseable {
        @Override public void close() { runtime.close(); }
        double capacity(String definition) {
            return runtime.state().engine().domain().buffs().instances().values().stream().filter(v -> v.definition().id().equals(definition))
                    .findFirst().orElseThrow().components().numbers().get("capacity");
        }
    }
    private static Shielded shielded(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
        var attacker = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2);
        try (var reader = new InputStreamReader(Objects.requireNonNull(NeoForgeDamagePipelineGameTest.class.getResourceAsStream("/effects/shields.json")), StandardCharsets.UTF_8)) {
            var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            var store = BuffStore.empty(); var origin = new BuffInstance.Origin(target.getUUID().toString(), "test-shield", "", "");
            for (String id : List.of("test:z_first", "test:a_second")) {
                store = Buffs.grant(store, program.buff(id), target.getUUID().toString(), target.getUUID().toString(), origin, 1, 1, BuffDefinition.FOREVER).store();
            }
            var runtime = MinecraftEffectRuntime.install(helper.getLevel(), program, EffectState.empty().withBuffs(store),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), _ -> { throw new AssertionError("Unexpected world command"); }, MinecraftEffectRuntime::nativeSource);
            return new Shielded(runtime, target, attacker);
        }
    }
    private static void near(GameTestHelper helper, double actual, double expected, String field) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-4, field + ": expected " + expected + ", got " + actual);
    }
    @GameCase public void incomingMutationChangesTheShieldBudget(GameTestHelper helper) throws Exception {
        String tag = "chorus_neo_incoming_" + UUID.randomUUID();
        NeoForge.EVENT_BUS.addListener((LivingIncomingDamageEvent event) -> {
            if (event.getEntity().entityTags().contains(tag)) event.setAmount(160);
        });
        try (var test = shielded(helper)) {
            test.target().addTag(tag); double health = test.target().getHealth();
            var result = MinecraftDamageExecutor.execute("incoming-change", test.target(), helper.getLevel().damageSources().mobAttack(test.attacker()), 200, false);
            near(helper, result.shieldLoss(), 55, "shield loss after incoming mutation");
            near(helper, result.shields().getFirst().trace().input(), 160, "budget reads changed container");
            near(helper, test.capacity("test:a_second"), 90, "second layer remaining");
            near(helper, test.target().getHealth(), health, "fully shielded health");
            helper.assertTrue(test.runtime().failure().isEmpty(), "Incoming mutation broke shield reconciliation");
        }
        helper.succeed();
    }
    @GameCase public void armorReductionModifierCannotRestoreConsumedChorusShield(GameTestHelper helper) throws Exception {
        String tag = "chorus_neo_armor_" + UUID.randomUUID();
        NeoForge.EVENT_BUS.addListener((LivingIncomingDamageEvent event) -> {
            if (event.getEntity().entityTags().contains(tag)) event.addReductionModifier(DamageContainer.Reduction.ARMOR, (_, _) -> 0);
        });
        try (var test = shielded(helper)) {
            test.target().addTag(tag); test.target().getAttribute(Attributes.ARMOR).setBaseValue(20);
            var result = MinecraftDamageExecutor.execute("armor-modifier", test.target(), helper.getLevel().damageSources().mobAttack(test.attacker()), 260, false);
            near(helper, result.shieldLoss(), 145, "Chorus loss is not an armor reduction");
            near(helper, result.healthLoss(), 10, "armor modifier only sees shield overflow");
            near(helper, test.capacity("test:z_first") + test.capacity("test:a_second"), 0, "both shields consumed");
            helper.assertTrue(test.runtime().failure().isEmpty(), "Armor modifier broke shield reconciliation");
        }
        helper.succeed();
    }
    @GameCase public void lateDamageMutationDoesNotUndoPriorShieldConsumption(GameTestHelper helper) throws Exception {
        String tag = "chorus_neo_late_" + UUID.randomUUID();
        var seen = new java.util.ArrayList<Float>();
        NeoForge.EVENT_BUS.addListener((LivingDamageEvent.Pre event) -> {
            if (event.getEntity().entityTags().contains(tag)) { seen.add(event.getNewDamage()); event.setNewDamage(0); }
        });
        try (var test = shielded(helper)) {
            test.target().addTag(tag); test.target().getAttribute(Attributes.ARMOR).setBaseValue(20);
            var result = MinecraftDamageExecutor.execute("late-mutation", test.target(), helper.getLevel().damageSources().mobAttack(test.attacker()), 270, false);
            helper.assertValueEqual(seen.size(), 1, "one post-armor hook"); near(helper, seen.getFirst(), 12, "post-shield post-armor input");
            near(helper, result.shieldLoss(), 145, "committed shield loss survives late zeroing");
            near(helper, result.healthLoss(), 0, "late zeroing prevents health loss");
            near(helper, test.capacity("test:z_first") + test.capacity("test:a_second"), 0, "late mutation does not refill shield");
            helper.assertTrue(test.runtime().failure().isEmpty(), "Late mutation broke shield reconciliation");
        }
        helper.succeed();
    }
}
