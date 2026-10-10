package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.platform.minecraft.MinecraftDamageExecutor;
import com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
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

public class DamageGameTest {
    private static LivingEntity target(GameTestHelper helper) { return helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); }
    private static void near(GameTestHelper helper, double actual, double expected, String field) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-4, field + ": expected " + expected + ", got " + actual);
    }
    private static DamageCommand command(LivingEntity target) {
        return new DamageCommand(target.getUUID().toString(), new BuffInstance.Origin("attacker", "perk", "weapon", ""),
                100, "minecraft:generic", Set.of(), Set.of("chorus:weapon_kill"), false);
    }

    @GameCase public void armorAndAbsorptionUseActualLoss(GameTestHelper helper) {
        var target = target(helper); double before = target.getHealth();
        target.getAttribute(Attributes.ARMOR).setBaseValue(20);
        target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0)); target.setAbsorptionAmount(3);
        var attacker = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2);
        var result = MinecraftDamageExecutor.execute("armor", target, helper.getLevel().damageSources().mobAttack(attacker), 10, false);
        near(helper, result.absorptionLoss(), 3, "absorption loss"); near(helper, result.healthLoss(), 1, "health loss after armor");
        near(helper, target.getHealth(), before - 1, "remaining health");
        helper.assertTrue(!result.lethal() && !result.deathPrevented(), "Nonfatal damage was marked fatal"); helper.succeed();
    }

    @GameCase public void totemProducesProtectionButNoDeathOrKill(GameTestHelper helper) {
        var target = target(helper); target.setHealth(6);
        target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0)); target.setAbsorptionAmount(4);
        target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        var result = MinecraftDamageExecutor.execute("totem", target, helper.getLevel().damageSources().generic(), 100, false);
        near(helper, result.healthLoss(), 6, "health lost before totem recovery"); near(helper, result.absorptionLoss(), 4, "absorption loss");
        helper.assertTrue(target.isAlive() && target.getHealth() > 0, "Totem did not save the entity");
        helper.assertTrue(target.getOffhandItem().isEmpty(), "Totem was not consumed");
        helper.assertTrue(result.deathPrevented() && !result.lethal(), "Protection was reported as death");
        helper.assertValueEqual(result.protectionSource(), Optional.of("minecraft:totem_of_undying"), "actual protection source");
        var facts = DamageFacts.from(command(target), result);
        helper.assertValueEqual(facts.stream().map(RuleEngine.Signal::type).toList(),
                List.of("chorus:hit", "chorus:damage_taken", "chorus:death_prevented"), "totem facts");
        helper.succeed();
    }

    @GameCase public void bypassedTotemStillConfirmsDeath(GameTestHelper helper) {
        var target = target(helper); target.setHealth(6);
        target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        var result = MinecraftDamageExecutor.execute("fatal", target, helper.getLevel().damageSources().genericKill(), 100, false);
        helper.assertTrue(!target.isAlive() && result.lethal() && !result.deathPrevented(), "Actual death not confirmed");
        helper.assertTrue(target.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "Bypassed protection was consumed");
        near(helper, result.healthLoss(), 6, "capped actual health loss");
        var facts = DamageFacts.from(command(target), result);
        var death = (EffectEvent) facts.get(2).payload(); var kill = (EffectEvent) facts.get(3).payload();
        helper.assertValueEqual(death.references().get("death_id"), kill.references().get("death_id"), "shared death ID");
        helper.succeed();
    }

    @GameCase public void nonLethalDamagePreservesHealthWithoutConsumingProtection(GameTestHelper helper) {
        var target = target(helper); target.setHealth(6);
        target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        var result = MinecraftDamageExecutor.execute("nonlethal", target, helper.getLevel().damageSources().generic(), 100, true);
        near(helper, target.getHealth(), 1, "minimum health"); near(helper, result.healthLoss(), 5, "actual health loss");
        helper.assertTrue(!result.lethal() && !result.deathPrevented(), "Nonlethal damage manufactured a death/protection");
        helper.assertTrue(target.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "Nonlethal damage consumed totem");
        target.damageCooldownTime = 0;
        var again = MinecraftDamageExecutor.execute("still-nonlethal", target, helper.getLevel().damageSources().generic(), 100, true);
        near(helper, target.getHealth(), 1, "remaining health at minimum"); near(helper, again.healthLoss(), 0, "loss at minimum");
        helper.succeed();
    }

    @GameCase public void cancellationAndImmunityRemainDistinct(GameTestHelper helper) {
        var target = target(helper); double before = target.getHealth(); target.setPermanentlyInvulnerable(true);
        var immune = MinecraftDamageExecutor.execute("immune", target, helper.getLevel().damageSources().generic(), 100, false);
        helper.assertValueEqual(immune.outcome(), DamageReceipt.Outcome.IMMUNE, "immune outcome");
        target.setPermanentlyInvulnerable(false); target.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 600, 0));
        var fire = MinecraftDamageExecutor.execute("fire", target, helper.getLevel().damageSources().inFire(), 100, false);
        helper.assertValueEqual(fire.outcome(), DamageReceipt.Outcome.IMMUNE, "fire immunity outcome");
        TestDamageHooks.ALLOW_DAMAGE.register((entity, _, _) -> !entity.entityTags().contains("chorus_test_cancelled"));
        target.addTag("chorus_test_cancelled");
        var cancelled = MinecraftDamageExecutor.execute("cancel", target, helper.getLevel().damageSources().generic(), 100, false);
        helper.assertValueEqual(cancelled.outcome(), DamageReceipt.Outcome.CANCELLED, "cancelled outcome");
        helper.assertTrue(DamageFacts.from(command(target), cancelled).isEmpty(), "Cancelled attack emitted hit facts");
        near(helper, target.getHealth(), before, "unchanged health"); helper.succeed();
    }

    @GameCase public void itemBlockingProducesAHitWithoutDamageTaken(GameTestHelper helper) {
        var target = target(helper); var attacker = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2);
        var shield = new ItemStack(Items.SHIELD); var defaults = shield.get(DataComponents.BLOCKS_ATTACKS);
        // Remove timing/angle requirements so this test isolates the native blocking receipt.
        shield.set(DataComponents.BLOCKS_ATTACKS, new BlocksAttacks(0, defaults.disableCooldownScale(),
                List.of(new BlocksAttacks.DamageReduction(360, Optional.empty(), 0, 1)), defaults.itemDamage(), defaults.bypassedBy(), defaults.blockSound(), defaults.disableSound()));
        target.setItemInHand(InteractionHand.OFF_HAND, shield); target.startUsingItem(InteractionHand.OFF_HAND);
        double before = target.getHealth();
        var receipt = MinecraftDamageExecutor.execute("blocked", target, helper.getLevel().damageSources().mobAttack(attacker), 4, false);
        helper.assertValueEqual(receipt.outcome(), DamageReceipt.Outcome.BLOCKED, "blocked outcome");
        near(helper, receipt.healthLoss(), 0, "blocked health loss"); near(helper, target.getHealth(), before, "remaining health");
        helper.assertValueEqual(DamageFacts.from(command(target), receipt).stream().map(RuleEngine.Signal::type).toList(), List.of("chorus:hit"), "blocked facts");
        helper.succeed();
    }

    @GameCase public void reentrantNativeDamageIsNotCountedInTheParentReceipt(GameTestHelper helper) {
        var target = target(helper); double before = target.getHealth(); target.addTag("chorus_test_nested");
        TestDamageHooks.ALLOW_DAMAGE.register((entity, source, _) -> {
            if (entity.entityTags().contains("chorus_test_nested")) {
                entity.removeTag("chorus_test_nested");
                entity.hurtServer(helper.getLevel(), source, 3);
                entity.damageCooldownTime = 0;
            }
            return true;
        });
        var receipt = MinecraftDamageExecutor.execute("parent", target, helper.getLevel().damageSources().generic(), 2, false);
        near(helper, receipt.healthLoss(), 2, "parent-only health loss"); near(helper, target.getHealth(), before - 5, "total world health loss");
        helper.succeed();
    }

    @GameCase public void sliceAcceptsTheLivingTargetAfterAnActualTotemSave(GameTestHelper helper) throws Exception {
        var target = target(helper); target.setHealth(6); target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/slice.json")), StandardCharsets.UTF_8);
             var strand = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/strand_defense.json")), StandardCharsets.UTF_8)) {
            var compiled = com.imdomestic.chorus.effect.data.CompiledEffects.link(List.of(
                    EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow(),
                    EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, JsonParser.parseReader(strand)).getOrThrow()));
            var origin = new BuffInstance.Origin("attacker", "perk", "weapon", "");
            var source = new EffectSource("perk", "chorus_d2:slice", "attacker", origin, Set.of());
            var world = new MinecraftWorldActions(helper.getLevel(), name -> name.equals("target") ? target : null,
                    _ -> helper.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            var session = new EffectSession(compiled.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1), EffectState.empty().withSource(source), world);
            session.start(0, new RuleEngine.Signal("chorus:class_ability_used", new EffectEvent("attacker", "target", origin, Set.of(), Map.of())));
            var command = new DamageCommand("target", origin, 100, "minecraft:generic", Set.of(), Set.of(), false);
            var receipt = MinecraftDamageExecutor.execute("slice-totem", target, helper.getLevel().damageSources().generic(), command.amount(), false);
            helper.assertTrue(receipt.deathPrevented() && target.isAlive(), "Expected successful protection");
            session.start(1_000_000, DamageFacts.from(command, receipt).getFirst());
            var instances = session.state().engine().domain().buffs().instances().values();
            helper.assertTrue(instances.stream().anyMatch(b -> b.definition().id().equals("chorus_d2:sever") && b.key().holder().equals("target")), "Live target did not receive status");
            helper.assertTrue(instances.stream().anyMatch(b -> b.definition().id().equals("chorus_d2:slice") && b.count() == 4), "Slice did not consume exactly one charge");
        }
        helper.succeed();
    }

    @GameCase public void serverPlayerOverrideConfirmsDeath(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel(); player.setHealth(6);
        var protectedWhileLoading = MinecraftDamageExecutor.execute("player-loading", player, helper.getLevel().damageSources().genericKill(), 100, false);
        helper.assertValueEqual(protectedWhileLoading.outcome(), DamageReceipt.Outcome.IMMUNE, "client loading immunity");
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        var receipt = MinecraftDamageExecutor.execute("player-death", player, helper.getLevel().damageSources().genericKill(), 100, false);
        helper.assertTrue(receipt.lethal() && !receipt.deathPrevented(), "ServerPlayer override did not confirm death: " + receipt);
        near(helper, receipt.healthLoss(), 6, "player health loss"); helper.succeed();
    }

    @GameCase public void jsonDamageResumesAndTriggersKillRulesInTheRealWorld(GameTestHelper helper) {
        var target = target(helper); var attacker = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2);
        var compiled = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"version":"gametest","buffs":[{"definition":{"id":"test:kills","version":"gametest","duration":"permanent","max_stacks":10}}],
                 "bundles":[{"id":"test:attack","rules":[
                   {"id":"attack","on":"test:attack","do":[{"type":"chorus:damage","amount":{"type":"chorus:constant","value":100,"unit":"damage"},"damage_type":"minecraft:mob_attack"}]},
                   {"id":"kill","on":"chorus:kill","do":[{"type":"chorus:grant_buff","buff":"test:kills"}]}
                 ]}]}
                """)).getOrThrow();
        var source = new EffectSource("perk", "test:attack", "attacker", new BuffInstance.Origin("attacker", "perk", "weapon", ""), Set.of());
        var entities = Map.of("attacker", attacker, "target", target);
        var world = new MinecraftWorldActions(helper.getLevel(), entities::get, _ -> helper.getLevel().damageSources().mobAttack(attacker), (_, _) -> true, _ -> {});
        var session = new EffectSession(compiled.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1), EffectState.empty().withSource(source), world);
        var hit = new RuleEngine.Signal("test:attack", new EffectEvent("attacker", "target", source.origin(), Set.of(), Map.<String, Measure>of()));
        session.start(0, hit);
        helper.assertTrue(!target.isAlive() && session.state().idle(), "World attack did not settle");
        helper.assertValueEqual(session.state().engine().domain().buffs().instances().values().iterator().next().count(), 1, "kill count");
        session.start(0, hit);
        helper.assertValueEqual(session.state().engine().domain().buffs().instances().values().iterator().next().count(), 1, "dead target must not award a second kill");
        helper.succeed();
    }
}
