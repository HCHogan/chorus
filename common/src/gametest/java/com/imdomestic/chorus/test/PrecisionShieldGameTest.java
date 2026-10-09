package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class PrecisionShieldGameTest {
    private static final String SHIELD = "chorus_d2:eternal_warrior_shield";
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h;
        final LivingEntity attacker, target;
        final EffectSource input, weapon, armor;
        final MinecraftEffectRuntime runtime;
        final List<DamageCommand> commands = new ArrayList<>();
        final List<DamageReceipt> receipts = new ArrayList<>();
        Harness(GameTestHelper helper, boolean enhanced, boolean flat) throws Exception {
            h = helper; attacker = h.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2); target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
            for (var entity : List.of(attacker, target)) { entity.setNoGravity(true); entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); }
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
            var fragments = new ArrayList<EffectProgram>();
            for (String file : List.of("eternal_warrior", "precision_damage")) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + file + ".json")), StandardCharsets.UTF_8)) {
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow());
            }
            var program = CompiledEffects.link(fragments); String owner = attacker.getUUID().toString(), victim = target.getUUID().toString();
            var origin = new BuffInstance.Origin(owner, "test:gun", "test:weapon", "");
            input = new EffectSource("input", "test:precision_inputs", owner, origin, Set.of());
            var tags = new HashSet<String>(); if (enhanced) tags.add("chorus:enhanced"); if (flat) tags.add("test:flat");
            weapon = new EffectSource("weapon", "test:precision_weapon", owner, origin, tags);
            armor = new EffectSource("armor", "chorus_d2:eternal_warrior", victim, new BuffInstance.Origin(victim, "test:armor", "", ""), Set.of());
            var initial = EffectState.empty().withSource(input).withSource(weapon).withSource(armor);
            if (flat) initial = initial.withSource(new EffectSource("defense", "test:flat_defense", victim, armor.origin(), Set.of()));
            var world = new MinecraftWorldActions(h.getLevel(), id -> id.equals(victim) ? target : id.equals(owner) ? attacker : null,
                    _ -> h.getLevel().damageSources().mobAttack(attacker), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, initial, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var result = world.apply(request);
                if (request.command() instanceof DamageCommand damage) { commands.add(damage); receipts.add((DamageReceipt) result); }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        void cast() { runtime.start(new RuleEngine.Signal("test:fists_start", new EffectEvent(armor.holder(), armor.holder(), armor.origin(), Set.of(), Map.of()))); settled(); }
        void send(String type, double amount, double delta) { runtime.start(new RuleEngine.Signal("test:" + type, new EffectEvent(input.holder(), target.getUUID().toString(), input.origin(), Set.of(), Map.of("amount", new Measure(amount, Unit.DAMAGE), "precision_delta", new Measure(delta, Unit.DELTA))))); settled(); }
        void settled() { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "precision runtime failed or suspended: " + runtime.failure()); }
        double capacity() { return runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals(SHIELD)).findFirst().orElseThrow().components().numbers().get("capacity"); }
        void at(int tick, Runnable action) { h.runAfterDelay(tick, () -> { try { settled(); action.run(); } catch (Throwable error) { close(); throw error; } }); }
        @Override public void close() { runtime.close(); attacker.discard(); target.discard(); }
    }
    @GameCase public void precisionSuppressionReplaysFlatDamageAndSpillsThroughAnotherShieldArmorAndAbsorption(GameTestHelper h) throws Exception {
        for (boolean enhanced : List.of(false, true)) try (var test = new Harness(h, enhanced, true)) {
            test.target.getAttribute(Attributes.ARMOR).setBaseValue(20);
            test.target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0)); test.target.setAbsorptionAmount(3);
            test.cast(); test.send("precision_plain", 0, 0); test.send("precision_fire", 10, 1);
            var receipt = test.receipts.getLast(); double perk = enhanced ? 1.5 : 1.25, full = 20 * perk + 6, body = 10 * perk + 6;
            double spill = full - 7.5 / (body / full) - 4; double afterArmor = spill * (1 - Math.max(4, 20 - spill / 2) / 25);
            near(h, receipt.outgoing().orElseThrow().output().value(), full - 4, "original precision attack"); near(h, receipt.defense().orElseThrow().output().value(), full, "original defense");
            var suppressed = receipt.shields().getFirst().factorSuppression().orElseThrow(); near(h, suppressed.multiplier(), body / full, "body projection ratio");
            near(h, suppressed.outgoing().orElseThrow().output().value(), body - 4, "attack flat damage retained"); near(h, suppressed.defense().orElseThrow().output().value(), body, "defense flat damage retained");
            h.assertTrue(receipt.shields().getLast().factorSuppression().isEmpty(), "next shield keeps precision budget");
            near(h, receipt.shieldLoss(), 11.5, "both capacities"); near(h, receipt.absorptionLoss(), 3, "native absorption"); near(h, receipt.healthLoss(), afterArmor - 3, "native health after shield armor absorption");
            near(h, test.target.getHealth(), 100 - afterArmor + 3, "actual health");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:precision_snapshot", maxTicks = 10)
    public void delayedAttackKeepsFactorDefinitionsAndSourceValuesWhileImpactAndShieldArriveLater(GameTestHelper h) throws Exception {
        var test = new Harness(h, true, true);
        try {
            test.send("precision_later", 10, 3); test.runtime.start(SourceChange.remove(test.weapon.instance())); test.runtime.start(SourceChange.remove(test.input.instance()));
            test.at(1, () -> { test.cast(); h.assertTrue(test.receipts.isEmpty(), "attack has not landed"); });
            test.at(2, () -> {
                try (test) {
                    h.assertValueEqual(test.receipts.size(), 1, "single delayed attack"); var receipt = test.receipts.getFirst();
                    near(h, receipt.outgoing().orElseThrow().output().value(), 62, "frozen enhanced perk and flat damage with impact x4");
                    near(h, receipt.outgoing().orElseThrow().trace().factors().getFirst().multiplier(), 4, "resolved factor retained");
                    near(h, receipt.shields().getFirst().factorSuppression().orElseThrow().defense().orElseThrow().output().value(), 21, "same hit without precision");
                    near(h, receipt.healthLoss(), 66 - 7.5 / (21 / 66.0), "critical overflow keeps budget basis");
                    h.assertTrue(test.commands.getFirst().snapshot().isPresent() && test.commands.getFirst().tags().contains("chorus:precision"), "snapshot and precision identity retained"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase public void rejectedPrecisionHitsKeepCapacityAndNativeCooldownAdmitsOnlyItsIncrement(GameTestHelper h) throws Exception {
        try (var test = new Harness(h, false, false)) {
            test.cast(); test.target.setPermanentlyInvulnerable(true); test.send("precision_fire", 2, 1);
            h.assertTrue(test.receipts.getLast().shields().isEmpty(), "immune hit has no suppression trace"); near(h, test.capacity(), 7.5, "immune capacity");
            test.target.setPermanentlyInvulnerable(false); test.send("precision_fire", 1, 1); near(h, test.capacity(), 6.25, "body amount enters shield");
            test.send("precision_fire", .5, 1); h.assertTrue(test.receipts.getLast().shields().isEmpty(), "cooldown rejected hit"); near(h, test.capacity(), 6.25, "rejected capacity");
            test.send("precision_fire", 2, 1); var receipt = test.receipts.getLast();
            near(h, receipt.outgoing().orElseThrow().output().value(), 5, "full new attack"); near(h, receipt.defense().orElseThrow().inputs().base().value(), 2.5, "admitted precision increment");
            near(h, receipt.shieldLoss(), 1.25, "suppression of admitted increment"); near(h, test.capacity(), 5, "final capacity"); near(h, test.target.getHealth(), 100, "no health damage");
        }
        h.succeed();
    }
}
