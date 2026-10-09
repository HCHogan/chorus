package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.core.component.DataComponents;

public class CombatProfileGameTest {
    private static final class Harness implements AutoCloseable {
        final MinecraftEffectRuntime runtime;
        final ServerLevel level;
        final LivingEntity attacker, target;
        final EffectSource source;
        final List<DamageReceipt> receipts = new ArrayList<>();
        Harness(GameTestHelper helper, LivingEntity target, boolean nativeScaling, double resistance) throws Exception {
            this(helper, target, nativeScaling, resistance, false);
        }
        Harness(GameTestHelper helper, LivingEntity target, boolean nativeScaling, double resistance, boolean flatDefense) throws Exception {
            this.level = helper.getLevel(); this.target = target;
            attacker = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2);
            var origin = new BuffInstance.Origin(attacker.getUUID().toString(), "test-source", "weapon", "");
            CompiledEffects compiled;
            try (var reader = new InputStreamReader(Objects.requireNonNull(CombatProfileGameTest.class.getResourceAsStream("/effects/combat_profiles.json")), StandardCharsets.UTF_8)) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                if (flatDefense) json.getAsJsonArray("profiles").get(1).getAsJsonObject().getAsJsonArray("steps").add(JsonParser.parseString("""
                        {"type":"chorus:curve","id":"flat","output_unit":"damage","curve":{
                          "type":"chorus:polynomial","coefficients":[7],"minimum":0,"maximum":1000,"boundary":"clamp"}}
                        """));
                for (var item : json.getAsJsonArray("bundles")) if (item.getAsJsonObject().get("id").getAsString().equals("test:resistance_active")) {
                    item.getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().getAsJsonObject("value").addProperty("value", resistance);
                }
                compiled = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, json).getOrThrow();
            }
            var store = BuffStore.empty();
            for (String id : List.of("chorus_d2:kill_clip", "chorus_d2:disruption_break", "test:resistance", "test:shield")) {
                String holder = id.equals("chorus_d2:kill_clip") ? attacker.getUUID().toString() : target.getUUID().toString();
                store = Buffs.grant(store, compiled.buff(id), holder, holder, origin, 1, 1, BuffDefinition.FOREVER).store();
            }
            source = new EffectSource("attack", "test:attack", attacker.getUUID().toString(), origin, Set.of());
            var world = new MinecraftWorldActions(level, id -> id.equals(target.getUUID().toString()) ? target : null,
                    _ -> target instanceof Player ? level.damageSources().genericKill() : level.damageSources().mobAttack(attacker), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(level, compiled, EffectState.empty().withBuffs(store).withSource(source),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        var receipt = world.apply(request); if (receipt instanceof DamageReceipt damage) receipts.add(damage); return receipt;
                    }, (victim, damage, amount) -> {
                        var nativeCommand = MinecraftEffectRuntime.nativeSource(victim, damage, amount);
                        // This explicit test host knows the synthetic weapon source; production does not guess it from held items.
                        return new DamageCommand(nativeCommand.target(), origin, amount, nativeCommand.damageType(), Set.of("chorus:kinetic_damage"), Set.of(), false,
                                nativeScaling ? Optional.of("test:weapon_damage") : Optional.empty());
                    });
        }
        DamageReceipt hit(double amount) {
            return MinecraftDamageExecutor.execute("test-" + UUID.randomUUID(), target, level.damageSources().mobAttack(attacker), amount, false);
        }
        DamageReceipt attack() {
            runtime.start(new RuleEngine.Signal("test:attack", new EffectEvent(source.holder(), target.getUUID().toString(), source.origin(), Set.of(), Map.of())));
            return receipts.getLast();
        }
        double capacity() {
            return runtime.state().engine().domain().buffs().instances().values().stream()
                    .filter(value -> value.definition().id().equals("test:shield")).findFirst().orElseThrow().components().numbers().get("capacity");
        }
        @Override public void close() { runtime.close(); }
    }
    private static void near(GameTestHelper helper, double actual, double expected, String field) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-4, field + ": expected " + expected + ", got " + actual);
    }
    @GameCase public void managedProfilesFlowThroughShieldArmorAndAbsorption(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
        try (var test = new Harness(helper, target, false, .5)) {
            target.getAttribute(Attributes.ARMOR).setBaseValue(20);
            target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0)); target.setAbsorptionAmount(3);
            double before = target.getHealth(); var result = test.attack();
            near(helper, result.outgoing().orElseThrow().output().value(), 20, "Kill Clip scaling");
            near(helper, result.defense().orElseThrow().trace().stages().get("vulnerability").value(), 30, "Disruption Break vulnerability");
            near(helper, result.defense().orElseThrow().output().value(), 15, "target resistance");
            near(helper, result.shieldLoss(), 5, "shield loss"); near(helper, result.absorptionLoss(), 3, "absorption loss after armor");
            near(helper, result.healthLoss(), 1, "remaining health loss"); near(helper, target.getHealth(), before - 1, "actual health");
            near(helper, test.capacity(), 0, "committed shield"); helper.assertTrue(test.runtime.failure().isEmpty(), "Profile runtime failed");
        }
        helper.succeed();
    }
    @GameCase public void ordinaryNativeDamageStillUsesTargetDefenseWithoutAnAttackProfile(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
        try (var test = new Harness(helper, target, false, .5)) {
            var result = test.hit(16);
            helper.assertTrue(result.outgoing().isEmpty(), "Guessed an attack profile from the source weapon");
            near(helper, result.defense().orElseThrow().output().value(), 12, "native target defense");
            near(helper, result.shieldLoss(), 5, "native shield loss"); near(helper, result.healthLoss(), 7, "native health loss");
            helper.assertTrue(test.runtime.failure().isEmpty(), "Native defense failed");
        }
        helper.succeed();
    }
    @GameCase public void playerSuperclassDelegationAppliesEachProfileOnce(GameTestHelper helper) throws Exception {
        var target = helper.makeMockServerPlayerInLevel(); target.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        try (var test = new Harness(helper, target, false, .5)) {
            double before = target.getHealth(); var result = test.attack();
            near(helper, result.outgoing().orElseThrow().output().value(), 20, "one outgoing calculation");
            near(helper, result.defense().orElseThrow().trace().stages().get("base").value(), 20, "defense sees one outgoing multiplier");
            near(helper, result.healthLoss(), 10, "player shield overflow"); near(helper, target.getHealth(), before - 10, "player actual health");
            near(helper, test.capacity(), 0, "player shield capacity");
        }
        helper.succeed();
    }
    @GameCase public void cancellationAndCooldownSkipDefenseAndOutgoingPrecedesCooldown(GameTestHelper helper) throws Exception {
        String tag = "chorus_profile_cancel_" + UUID.randomUUID();
        TestDamageHooks.ALLOW_DAMAGE.register((target, _, _) -> !target.entityTags().contains(tag));
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
        try (var test = new Harness(helper, target, true, .5)) {
            target.addTag(tag); var cancelled = test.hit(4); target.removeTag(tag);
            helper.assertTrue(cancelled.outgoing().isPresent() && cancelled.defense().isEmpty(), "Cancelled attack entered defense phase");
            near(helper, test.capacity(), 5, "cancelled shield");
            var first = test.hit(4); near(helper, first.defense().orElseThrow().output().value(), 3.75, "first defense");
            var repeat = test.hit(4); helper.assertValueEqual(repeat.outcome(), DamageReceipt.Outcome.CANCELLED, "native cooldown rejection");
            helper.assertTrue(repeat.defense().isEmpty(), "Cooldown rejection calculated defense");
            var stronger = test.hit(6);
            near(helper, stronger.outgoing().orElseThrow().output().value(), 7.5, "outgoing before native difference");
            near(helper, stronger.defense().orElseThrow().trace().stages().get("base").value(), 2.5, "cooldown increment feeds defense");
            near(helper, stronger.healthLoss(), .625, "increment after resistance and remaining shield");
            helper.assertTrue(test.runtime.failure().isEmpty(), "Cooldown profile flow failed");
        }
        helper.succeed();
    }
    @GameCase public void completeResistanceBlocksWithoutSpendingShield(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
        try (var test = new Harness(helper, target, true, 1)) {
            double before = target.getHealth(); var result = test.hit(16);
            helper.assertValueEqual(result.outcome(), DamageReceipt.Outcome.BLOCKED, "zero profile result blocks");
            near(helper, result.effective(true), 0, "blocked loss"); near(helper, test.capacity(), 5, "blocked shield");
            near(helper, target.getHealth(), before, "blocked health");
            helper.assertValueEqual(DamageFacts.from(new DamageCommand(target.getUUID().toString(), test.source.origin(), 16, "minecraft:mob_attack", Set.of(), Set.of(), false), result)
                    .stream().map(RuleEngine.Signal::type).toList(), List.of("chorus:hit"), "only zero-loss hit fact");
        }
        helper.succeed();
    }
    @GameCase public void nestedDamageRetainsIndependentProfileTracesAndShieldWrites(GameTestHelper helper) throws Exception {
        String tag = "chorus_profile_nested_" + UUID.randomUUID(); var child = new ArrayList<DamageReceipt>();
        TestDamageHooks.AFTER_DAMAGE.register((target, source, _, _, _) -> {
            if (target.entityTags().contains(tag)) {
                target.removeTag(tag); target.damageCooldownTime = 0;
                child.add(MinecraftDamageExecutor.execute("nested-profile", target, source, 4, false));
            }
        });
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
        try (var test = new Harness(helper, target, true, .5)) {
            double before = target.getHealth(); target.addTag(tag); var parent = test.hit(4);
            helper.assertValueEqual(child.size(), 1, "one nested hit");
            for (var receipt : List.of(parent, child.getFirst())) {
                near(helper, receipt.outgoing().orElseThrow().output().value(), 5, "independent outgoing");
                near(helper, receipt.defense().orElseThrow().output().value(), 3.75, "independent defense");
            }
            near(helper, parent.shieldLoss(), 3.75, "parent shield"); near(helper, parent.healthLoss(), 0, "parent excludes child");
            near(helper, child.getFirst().shieldLoss(), 1.25, "child shield"); near(helper, child.getFirst().healthLoss(), 2.5, "child health");
            near(helper, target.getHealth(), before - 2.5, "nested health"); near(helper, test.capacity(), 0, "nested committed capacity");
            helper.assertTrue(test.runtime.failure().isEmpty(), "Nested profiles broke reconciliation");
        }
        helper.succeed();
    }
    @GameCase public void explicitProfileWithoutRuntimeFailsBeforeWorldDamage(GameTestHelper helper) {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); double before = target.getHealth();
        var world = new MinecraftWorldActions(helper.getLevel(), _ -> target, _ -> helper.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
        var command = new DamageCommand(target.getUUID().toString(), new BuffInstance.Origin("owner", "test", "", ""), 3, "minecraft:generic", Set.of(), Set.of(), false, Optional.of("test:profile"));
        boolean rejected = false;
        try { world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), command)); }
        catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "Explicit profile was silently ignored without runtime"); near(helper, target.getHealth(), before, "unexecuted health");
        helper.succeed();
    }
    @GameCase public void flatDefenseCannotResurrectCompletelyItemBlockedDamage(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
        try (var test = new Harness(helper, target, true, .5, true)) {
            var item = new ItemStack(Items.SHIELD); var defaults = item.get(DataComponents.BLOCKS_ATTACKS);
            item.set(DataComponents.BLOCKS_ATTACKS, new BlocksAttacks(0, defaults.disableCooldownScale(),
                    List.of(new BlocksAttacks.DamageReduction(360, Optional.empty(), 0, 1)), defaults.itemDamage(), defaults.bypassedBy(), defaults.blockSound(), defaults.disableSound()));
            target.setItemInHand(InteractionHand.OFF_HAND, item); target.startUsingItem(InteractionHand.OFF_HAND);
            var blocked = test.hit(4);
            helper.assertValueEqual(blocked.outcome(), DamageReceipt.Outcome.BLOCKED, "item block");
            helper.assertTrue(blocked.defense().isEmpty(), "Fully blocked input reached flat defense curve");
            near(helper, blocked.effective(true), 0, "blocked loss"); near(helper, test.capacity(), 5, "blocked shield");
            target.stopUsingItem(); target.damageCooldownTime = 0;
            var allowed = test.hit(4);
            near(helper, allowed.defense().orElseThrow().output().value(), 7, "unblocked curve is actually active");
            near(helper, allowed.healthLoss(), 2, "flat curve shield overflow");
            helper.assertTrue(test.runtime.failure().isEmpty(), "Flat profile flow failed");
        }
        helper.succeed();
    }
}
