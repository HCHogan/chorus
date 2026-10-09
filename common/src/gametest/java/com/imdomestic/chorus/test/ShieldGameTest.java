package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.core.component.DataComponents;

public class ShieldGameTest {
    private record Fact(RuleEngine.Event event) implements RuleEngine.WorldCommand {}
    private record Observe() implements Action {
        @Override public ResultShape validate(Validation v) { return ResultShape.EMPTY; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(new Fact(e.context().event())); }
    }
    private static final class Harness implements AutoCloseable {
        final MinecraftEffectRuntime runtime;
        final ServerLevel level;
        final LivingEntity attacker;
        final EffectSource source;
        final Map<String, LivingEntity> targets = new HashMap<>();
        final List<RuleEngine.Event> facts = new ArrayList<>();
        Harness(GameTestHelper helper, boolean compact, double multiplier) throws Exception {
            level = helper.getLevel(); attacker = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2);
            var json = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(ShieldGameTest.class.getResourceAsStream("/effects/shields.json")), StandardCharsets.UTF_8)).getAsJsonObject();
            json.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().add("impact", JsonParser.parseString("""
                    {"before_shield":{"type":"chorus:component","buff":"test:z_first","target":"victim","component":"capacity"}}
                    """));
            if (compact) {
                var buffs = json.getAsJsonArray("buffs");
                buffs.get(0).getAsJsonObject().getAsJsonObject("definition").getAsJsonObject("components").getAsJsonObject("numbers").getAsJsonObject("capacity").addProperty("initial", 6);
                buffs.get(1).getAsJsonObject().getAsJsonObject("definition").getAsJsonObject("components").getAsJsonObject("numbers").getAsJsonObject("capacity").addProperty("initial", 0);
                buffs.get(0).getAsJsonObject().getAsJsonObject("shield").add("taken_multiplier", JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":" + multiplier + ",\"unit\":\"multiplier\"}"));
            }
            var decoded = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, json).getOrThrow().program();
            var rules = new ArrayList<>(decoded.bundles().getFirst().rules());
            for (String type : List.of("hit", "damage_taken", "shield_damaged", "shield_broken", "death", "kill", "death_prevented")) {
                rules.add(new EffectProgram.Rule("observe_" + type, "chorus:" + type, new Condition.Constant(true), List.of(new EffectProgram.Instruction(new Observe(), ""))));
            }
            var compiled = new CompiledEffects(new EffectProgram(decoded.version(), decoded.buffs(),
                    List.of(new EffectProgram.Bundle("test:shield_actions", EffectProgram.Scope.SOURCE, rules, List.of())), List.of()));
            source = new EffectSource("shield-test-source", "test:shield_actions", attacker.getUUID().toString(),
                    new BuffInstance.Origin(attacker.getUUID().toString(), "shield-test-source", "weapon", ""), Set.of());
            var world = new MinecraftWorldActions(level, targets::get, _ -> level.damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(level, compiled, EffectState.empty().withSource(source), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof Fact fact) { facts.add(fact.event()); return RuleEngine.Empty.INSTANCE; }
                // This test host permits consecutive managed hits; the separate native test verifies cooldown rejection.
                if (request.command() instanceof DamageCommand damage) targets.get(damage.target()).damageCooldownTime = 0;
                return world.apply(request);
            }, MinecraftEffectRuntime::nativeSource);
        }
        LivingEntity target(GameTestHelper helper) {
            var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); arm(target); return target;
        }
        void arm(LivingEntity target) { targets.put(target.getUUID().toString(), target); start("test:arm", target); }
        void start(String type, LivingEntity target) {
            runtime.start(new RuleEngine.Signal(type, new EffectEvent(source.holder(), target.getUUID().toString(), source.origin(), Set.of(), Map.of())));
        }
        double capacity(LivingEntity target, String definition) {
            return runtime.state().engine().domain().buffs().instances().values().stream()
                    .filter(value -> value.key().holder().equals(target.getUUID().toString()) && value.definition().id().equals(definition))
                    .findFirst().orElseThrow().components().numbers().get("capacity");
        }
        List<EffectEvent> hits() { return facts.stream().filter(e -> e.signal().type().equals("chorus:hit")).map(e -> (EffectEvent) e.signal().payload()).toList(); }
        @Override public void close() { runtime.close(); }
    }
    private static void near(GameTestHelper helper, double actual, double expected, String field) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-4, field + ": expected " + expected + ", got " + actual);
    }

    @GameCase public void fifoLayersSpillIntoArmorThenAbsorption(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper, false, 1)) {
            var target = test.target(helper); double health = target.getHealth();
            target.getAttribute(Attributes.ARMOR).setBaseValue(20);
            target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0)); target.setAbsorptionAmount(3);
            target.hurtServer(test.level, test.level.damageSources().mobAttack(test.attacker), 200);
            near(helper, test.hits().getFirst().numbers().get("shield_loss").value(), 95, "two-layer capacity loss");
            near(helper, test.capacity(target, "test:a_second"), 50, "remaining second layer");
            near(helper, target.getHealth(), health, "fully shielded health"); near(helper, target.getAbsorptionAmount(), 3, "fully shielded absorption");
            helper.assertValueEqual(test.facts.stream().filter(e -> e.signal().type().equals("chorus:shield_broken")).count(), 1L, "one broken layer");
            test.facts.clear(); target.damageCooldownTime = 0;
            target.hurtServer(test.level, test.level.damageSources().mobAttack(test.attacker), 60);
            var hit = test.hits().getFirst(); near(helper, hit.numbers().get("shield_loss").value(), 50, "second shield loss");
            near(helper, hit.numbers().get("absorption_loss").value(), 3, "absorption after armor");
            near(helper, hit.numbers().get("health_loss").value(), 1, "health after shield, armor and absorption");
            near(helper, target.getHealth(), health - 1, "native remaining health");
            helper.assertTrue(test.runtime.failure().isEmpty(), "Shield runtime failed");
        }
        helper.succeed();
    }

    @GameCase public void cancellationImmunityAndCooldownDoNotSpendShield(GameTestHelper helper) throws Exception {
        String cancel = "chorus_shield_cancel_" + UUID.randomUUID();
        TestDamageHooks.ALLOW_DAMAGE.register((entity, _, _) -> !entity.entityTags().contains(cancel));
        try (var test = new Harness(helper, true, 1)) {
            var target = test.target(helper); target.addTag(cancel);
            target.hurtServer(test.level, test.level.damageSources().generic(), 100);
            near(helper, test.capacity(target, "test:z_first"), 6, "cancelled shield unchanged"); helper.assertTrue(test.facts.isEmpty(), "Cancelled damage published facts");
            target.removeTag(cancel); target.setPermanentlyInvulnerable(true);
            target.hurtServer(test.level, test.level.damageSources().generic(), 100);
            near(helper, test.capacity(target, "test:z_first"), 6, "immune shield unchanged");
            target.setPermanentlyInvulnerable(false); test.facts.clear();
            target.hurtServer(test.level, test.level.damageSources().generic(), 2);
            near(helper, test.capacity(target, "test:z_first"), 4, "accepted shield loss");
            test.facts.clear(); target.hurtServer(test.level, test.level.damageSources().generic(), 2);
            near(helper, test.capacity(target, "test:z_first"), 4, "cooldown rejection must not spend shield");
            helper.assertTrue(test.facts.isEmpty(), "Cooldown rejection published shield damage");
        }
        helper.succeed();
    }

    @GameCase public void itemBlockingAndImmuneShieldAreDistinct(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper, true, 0)) {
            var target = test.target(helper); double health = target.getHealth();
            target.hurtServer(test.level, test.level.damageSources().generic(), 100);
            helper.assertValueEqual(test.facts.size(), 1, "immune layer only hit fact");
            helper.assertTrue(test.hits().getFirst().flags().get("blocked"), "Immune shield did not block");
            near(helper, test.capacity(target, "test:z_first"), 6, "immune layer capacity"); near(helper, target.getHealth(), health, "immune layer health");
        }
        try (var test = new Harness(helper, true, 1)) {
            var target = test.target(helper); var shield = new ItemStack(Items.SHIELD); var defaults = shield.get(DataComponents.BLOCKS_ATTACKS);
            shield.set(DataComponents.BLOCKS_ATTACKS, new BlocksAttacks(0, defaults.disableCooldownScale(),
                    List.of(new BlocksAttacks.DamageReduction(360, Optional.empty(), 0, 1)), defaults.itemDamage(), defaults.bypassedBy(), defaults.blockSound(), defaults.disableSound()));
            target.setItemInHand(InteractionHand.OFF_HAND, shield); target.startUsingItem(InteractionHand.OFF_HAND);
            target.hurtServer(test.level, test.level.damageSources().mobAttack(test.attacker), 4);
            near(helper, test.capacity(target, "test:z_first"), 6, "item-blocked Chorus shield unchanged");
            helper.assertValueEqual(test.facts.size(), 1, "item blocking has no shield loss fact");
        }
        helper.succeed();
    }

    @GameCase public void managedNextActionReadsTheCommittedRemainingShield(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper, false, 1)) {
            var target = test.target(helper); double health = target.getHealth(); test.start("test:attack", target);
            var hits = test.hits(); helper.assertValueEqual(hits.size(), 2, "managed hit count");
            near(helper, hits.get(0).numbers().get("shield_loss").value(), 95, "first managed shield loss");
            near(helper, hits.get(0).impact().number("before_shield", com.imdomestic.chorus.stat.Unit.DAMAGE).value(), 45, "issued measurement survives shield reconciliation before completion");
            near(helper, hits.get(1).numbers().get("shield_loss").value(), 50, "second action reads 50 remaining capacity");
            near(helper, test.capacity(target, "test:a_second"), 0, "committed second layer");
            near(helper, target.getHealth(), health, "managed shield-only attacks leave health intact");
            helper.assertTrue(test.runtime.failure().isEmpty(), "Managed shield commit failed");
        }
        helper.succeed();
    }

    @GameCase public void nestedAfterDamageUsesTheAlreadyConsumedShield(GameTestHelper helper) throws Exception {
        String nested = "chorus_shield_nested_" + UUID.randomUUID();
        TestDamageHooks.AFTER_DAMAGE.register((target, _, _, _, _) -> {
            if (target.entityTags().contains(nested)) {
                target.removeTag(nested); target.damageCooldownTime = 0;
                var level = (ServerLevel) target.level(); target.hurtServer(level, level.damageSources().generic(), 4);
            }
        });
        try (var test = new Harness(helper, true, 1)) {
            var target = test.target(helper); double health = target.getHealth(); target.addTag(nested);
            target.hurtServer(test.level, test.level.damageSources().generic(), 4);
            var hits = test.hits(); helper.assertValueEqual(hits.size(), 2, "nested shield hit count");
            near(helper, hits.get(0).numbers().get("shield_loss").value(), 2, "child sees only remaining two shield");
            near(helper, hits.get(0).numbers().get("health_loss").value(), 2, "child spillover");
            near(helper, hits.get(1).numbers().get("shield_loss").value(), 4, "parent retained independent shield receipt");
            near(helper, hits.get(1).numbers().get("health_loss").value(), 0, "parent health receipt excludes child");
            near(helper, target.getHealth(), health - 2, "nested damage total"); near(helper, test.capacity(target, "test:z_first"), 0, "no shield double spending");
            helper.assertTrue(test.runtime.failure().isEmpty(), "Nested sequential writes failed reconciliation");
        }
        helper.succeed();
    }

    @GameCase public void playerOverrideConsumesShieldBeforeNativeHealth(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper, true, 1)) {
            var player = helper.makeMockServerPlayerInLevel(); player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            test.arm(player); double health = player.getHealth();
            player.hurtServer(test.level, test.level.damageSources().genericKill(), 4);
            near(helper, test.capacity(player, "test:z_first"), 2, "player shield capacity"); near(helper, player.getHealth(), health, "player shielded health");
            helper.assertValueEqual(test.hits().size(), 1, "one player shield hit");
        }
        helper.succeed();
    }

    @GameCase public void exceptionAfterShieldConsumptionRetainsUnreconciledWrites(GameTestHelper helper) throws Exception {
        String fail = "chorus_shield_fail_" + UUID.randomUUID();
        TestDamageHooks.AFTER_DAMAGE.register((target, _, _, _, _) -> {
            if (target.entityTags().contains(fail)) { target.removeTag(fail); throw new IllegalStateException("expected failure after shield consumption"); }
        });
        try (var test = new Harness(helper, true, 1)) {
            var target = test.target(helper); target.addTag(fail); boolean threw = false;
            try { target.hurtServer(test.level, test.level.damageSources().generic(), 4); }
            catch (IllegalStateException expected) { threw = true; }
            helper.assertTrue(threw, "Native post-shield failure was swallowed");
            var failure = test.runtime.failure().orElseThrow(); helper.assertValueEqual(failure.committedShields().writes().size(), 1, "retained shield write on failure");
            near(helper, failure.committedShields().writes().getFirst().after(), 2, "retained consumed shield capacity");
            helper.assertTrue(test.facts.isEmpty(), "Incomplete native hit published a guessed receipt");
        }
        helper.succeed();
    }
}
