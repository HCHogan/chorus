package com.imdomestic.chorus.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class HealthLayerGameTest {
    static final class Harness implements AutoCloseable {
        final MinecraftEffectRuntime runtime;
        final LivingEntity target, attacker;
        final ServerLevel level;
        final EffectSource source;
        final List<DamageReceipt> receipts = new ArrayList<>();
        Harness(GameTestHelper h, boolean player, Set<String> buffs, Consumer<JsonObject> edit) {
            level = h.getLevel(); target = player ? NativeMeleeGameTest.player(h) : h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
            attacker = h.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
            var data = ThreadedSpikeGameTest.json("health_layer"); edit.accept(data);
            var p = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
            var origin = new BuffInstance.Origin(attacker.getUUID().toString(), "health-source", "weapon", "");
            source = new EffectSource("health-source", "test:attack", origin.owner(), origin, Set.of());
            var store = BuffStore.empty();
            for (String buff : buffs) store = Buffs.grant(store, p.buff("test:" + buff), target.getUUID().toString(), target.getUUID().toString(), origin, 1, 1, BuffDefinition.FOREVER).store();
            var world = new MinecraftWorldActions(level, id -> id.equals(target.getUUID().toString()) ? target : null,
                    _ -> level.damageSources().mobAttack(attacker), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(level, p, EffectState.empty().withBuffs(store).withSource(source),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        var result = world.apply(request); if (result instanceof DamageReceipt r) receipts.add(r); return result;
                    }, MinecraftEffectRuntime::nativeSource);
        }
        DamageReceipt hit(double amount, boolean nonLethal) {
            return MinecraftDamageExecutor.execute("health/" + UUID.randomUUID(), target, level.damageSources().mobAttack(attacker), amount, nonLethal);
        }
        DamageReceipt managed() {
            runtime.start(new RuleEngine.Signal("test:attack", new EffectEvent(source.holder(), target.getUUID().toString(), source.origin(), Set.of(), Map.of())));
            return receipts.getLast();
        }
        double capacity() { return runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:shield")).findFirst().orElseThrow().components().numbers().get("capacity"); }
        void absorption(float amount) { target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 1)); target.setAbsorptionAmount(amount); }
        @Override public void close() { runtime.close(); target.discard(); attacker.discard(); }
    }
    static void near(GameTestHelper h, double actual, double expected, String label) {
        h.assertTrue(Math.abs(actual - expected) < 1e-4, label + ": expected " + expected + ", got " + actual);
    }
    static void flat(JsonObject data, double output) {
        data.getAsJsonArray("profiles").get(2).getAsJsonObject().getAsJsonArray("steps").add(JsonParser.parseString("""
                {"type":"chorus:curve","id":"flat","output_unit":"damage","curve":{"type":"chorus:polynomial","coefficients":[%s],"minimum":0,"maximum":1000,"boundary":"clamp"}}
                """.formatted(output)));
    }
    @GameCase public void managedAndNativeHitsScaleOnlyThePostArmorPostAbsorptionHealthBudget(GameTestHelper h) {
        for (boolean player : List.of(false, true)) for (boolean managed : List.of(false, true)) {
            try (var t = new Harness(h, player, Set.of("global", "health", "shield"), _ -> {})) {
                t.target.getAttribute(Attributes.ARMOR).setBaseValue(20); t.absorption(4);
                var r = managed ? t.managed() : t.hit(80, false);
                near(h, r.defense().orElseThrow().output().value(), 40, "global defense");
                near(h, r.shieldLoss(), 10, "shield loss"); near(h, r.absorptionLoss(), 4, "absorption loss");
                near(h, r.health().orElseThrow().trace().stages().get("base").value(), 20, "HP profile after native armor and absorption");
                near(h, r.health().orElseThrow().output().value(), 5, "one HP scaling"); near(h, r.healthLoss(), 5, "actual HP loss");
                near(h, t.target.getHealth(), 95, "actual HP"); near(h, t.capacity(), 0, "committed shield");
                h.assertTrue(r.outgoing().isPresent() == managed, "native attack guessed outgoing scaling");
                h.assertTrue(t.runtime.failure().isEmpty(), "health-layer runtime failed");
            }
        }
        h.succeed();
    }
    @GameCase public void shieldAndAbsorptionZeroBudgetsDoNotActivateFlatHealthStages(GameTestHelper h) {
        for (boolean player : List.of(false, true)) {
            try (var t = new Harness(h, player, Set.of("shield"), d -> flat(d, 7))) {
                t.absorption(4); var r = t.hit(5, false);
                near(h, r.shieldLoss(), 5, "partial shield"); near(h, r.absorptionLoss(), 0, "shielded absorption");
                near(h, r.healthLoss(), 0, "shielded HP"); h.assertTrue(r.health().isEmpty(), "flat HP queried behind shield");
            }
            try (var t = new Harness(h, player, Set.of(), d -> flat(d, 7))) {
                t.absorption(4); var r = t.hit(3, false);
                near(h, r.absorptionLoss(), 3, "partial absorption"); near(h, t.target.getAbsorptionAmount(), 1, "remaining absorption");
                near(h, r.healthLoss(), 0, "absorbed HP"); h.assertTrue(r.health().isEmpty(), "flat HP queried behind absorption");
            }
        }
        h.succeed();
    }
    @GameCase public void zeroHealthMultiplierBlocksOnlyHealthAndPreservesEarlierLosses(GameTestHelper h) {
        for (boolean player : List.of(false, true)) for (boolean absorption : List.of(false, true)) {
            try (var t = new Harness(h, player, Set.of(), d -> flat(d, 0))) {
                if (absorption) t.absorption(4);
                var r = t.hit(10, false);
                near(h, r.healthLoss(), 0, "immune health"); near(h, r.absorptionLoss(), absorption ? 4 : 0, "prior absorption");
                near(h, r.health().orElseThrow().output().value(), 0, "zero profile");
                h.assertValueEqual(r.outcome(), absorption ? DamageReceipt.Outcome.APPLIED : DamageReceipt.Outcome.BLOCKED, "health-only outcome");
                near(h, t.target.getHealth(), 100, "blocked HP");
            }
        }
        h.succeed();
    }
    @GameCase public void cancellationImmunityAndCooldownNeverReachTheHealthProfile(GameTestHelper h) {
        String tag = "health_cancel_" + UUID.randomUUID();
        TestDamageHooks.ALLOW_DAMAGE.register((target, _, _) -> !target.entityTags().contains(tag));
        try (var t = new Harness(h, false, Set.of("health"), _ -> {})) {
            t.target.addTag(tag); h.assertTrue(t.hit(10, false).health().isEmpty(), "cancelled health query");
            t.target.removeTag(tag); t.target.setPermanentlyInvulnerable(true);
            h.assertTrue(t.hit(10, false).health().isEmpty(), "immune health query"); t.target.setPermanentlyInvulnerable(false);
            near(h, t.hit(10, false).healthLoss(), 2.5, "first health loss");
            h.assertTrue(t.hit(10, false).health().isEmpty(), "cooldown health query");
            near(h, t.hit(14, false).healthLoss(), 1, "only cooldown delta reaches health");
            h.assertTrue(t.runtime.failure().isEmpty(), "gates failed runtime");
        }
        h.succeed();
    }
    @GameCase public void transformedDamageKeepsNativeDeathProtectionAndNonlethalFloors(GameTestHelper h) {
        for (boolean player : List.of(false, true)) {
            try (var t = new Harness(h, player, Set.of("health"), _ -> {})) {
                t.target.setHealth(2); var r = t.hit(40, true);
                near(h, r.health().orElseThrow().output().value(), 10, "pre-floor HP plan");
                near(h, r.healthLoss(), 1, "nonlethal actual HP"); near(h, t.target.getHealth(), 1, "HP floor");
            }
            try (var t = new Harness(h, player, Set.of("health"), _ -> {})) {
                t.target.setHealth(2); t.target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
                var r = t.hit(40, false);
                h.assertTrue(r.deathPrevented() && !r.lethal(), "transformed damage bypassed protection");
                near(h, r.healthLoss(), 2, "actual lethal write before protection");
                near(h, r.health().orElseThrow().output().value(), 10, "protection preserves trace");
            }
            try (var t = new Harness(h, player, Set.of("health"), _ -> {})) {
                t.target.setHealth(2); var r = t.hit(40, false);
                h.assertTrue(r.lethal(), "transformed lethal damage missing death"); near(h, r.healthLoss(), 2, "lethal actual HP");
            }
        }
        h.succeed();
    }
    @GameCase public void nestedNativeDamageUsesItsOwnHealthBudgetAndTrace(GameTestHelper h) {
        String tag = "health_nested_" + UUID.randomUUID(); var nested = new ArrayList<DamageReceipt>();
        TestDamageHooks.AFTER_DAMAGE.register((target, source, _, _, _) -> {
            if (!target.entityTags().contains(tag)) return;
            target.removeTag(tag); target.damageCooldownTime = 0;
            nested.add(MinecraftDamageExecutor.execute("nested-health", target, source, 8, false));
        });
        try (var t = new Harness(h, false, Set.of("health"), _ -> {})) {
            t.target.addTag(tag); var outer = t.hit(20, false);
            h.assertValueEqual(nested.size(), 1, "nested call");
            near(h, outer.healthLoss(), 5, "outer isolated HP loss"); near(h, nested.getFirst().healthLoss(), 2, "inner HP loss");
            near(h, outer.health().orElseThrow().trace().stages().get("base").value(), 20, "outer input");
            near(h, nested.getFirst().health().orElseThrow().trace().stages().get("base").value(), 8, "inner input");
            near(h, t.target.getHealth(), 93, "combined HP"); h.assertTrue(t.runtime.failure().isEmpty(), "nested runtime failed");
        }
        h.succeed();
    }
    @GameCase public void unknownHealthQueryKeepsPriorShieldAndAbsorptionWritesWithoutApplyingFallbackDamage(GameTestHelper h) {
        for (boolean player : List.of(false, true)) {
            try (var t = new Harness(h, player, Set.of("shield"), d -> flat(d, -1))) {
                t.absorption(4);
                try { t.hit(20, false); throw new AssertionError("negative HP result accepted"); }
                catch (IllegalArgumentException expected) { h.assertTrue(expected.getMessage().contains("damage profile output"), "unexpected failure: " + expected); }
                h.assertTrue(t.runtime.failure().isPresent(), "unknown HP result did not fail runtime");
                near(h, t.target.getHealth(), 100, "no fallback HP damage"); near(h, t.target.getAbsorptionAmount(), 0, "native absorption remains spent");
                var writes = t.runtime.failure().orElseThrow().committedShields().writes();
                h.assertValueEqual(writes.size(), 1, "retained shield write count");
                near(h, writes.getFirst().after(), 0, "retained prior shield depletion");
                t.runtime.prepare(); t.runtime.prepare(); near(h, t.target.getHealth(), 100, "unknown operation not replayed");
            }
        }
        h.succeed();
    }
}
