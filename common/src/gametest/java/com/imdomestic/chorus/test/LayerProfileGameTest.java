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

public class LayerProfileGameTest {
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h;
        final LivingEntity attacker, target;
        final EffectSource input, perk;
        final MinecraftEffectRuntime runtime;
        final List<DamageCommand> commands = new ArrayList<>();
        final List<DamageReceipt> receipts = new ArrayList<>();
        Harness(GameTestHelper helper, boolean enhanced) throws Exception {
            h = helper; attacker = h.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2); target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
            for (var entity : List.of(attacker, target)) { entity.setNoGravity(true); entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); }
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
            var fragments = new ArrayList<EffectProgram>();
            for (String file : List.of("under_over", "shield_scaling")) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + file + ".json")), StandardCharsets.UTF_8)) {
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow());
            }
            var program = CompiledEffects.link(fragments); String owner = attacker.getUUID().toString();
            var origin = new BuffInstance.Origin(owner, "test:gun", "test:weapon", "");
            input = new EffectSource("input", "test:layer_inputs", owner, origin, Set.of());
            perk = new EffectSource("perk", "chorus_d2:under_over", owner, origin, enhanced ? Set.of("chorus:enhanced") : Set.of());
            var world = new MinecraftWorldActions(h.getLevel(), id -> id.equals(target.getUUID().toString()) ? target : id.equals(owner) ? attacker : null,
                    _ -> h.getLevel().damageSources().mobAttack(attacker), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withSource(input).withSource(perk), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var result = world.apply(request);
                if (request.command() instanceof DamageCommand damage) { commands.add(damage); receipts.add((DamageReceipt) result); }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        void send(String type, double amount) { runtime.start(new RuleEngine.Signal("test:" + type, new EffectEvent(input.holder(), target.getUUID().toString(), input.origin(), Set.of(), Map.of("amount", new Measure(amount, Unit.DAMAGE))))); settled(); }
        void settled() { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "layer profile runtime failed or suspended"); }
        double capacity(String id) { return runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:" + id)).findFirst().orElseThrow().components().numbers().get("capacity"); }
        void at(int tick, Runnable action) { h.runAfterDelay(tick, () -> { try { settled(); action.run(); } catch (Throwable error) { close(); throw error; } }); }
        @Override public void close() { runtime.close(); attacker.discard(); target.discard(); }
    }
    @GameCase public void perLayerAttackScalingSpillsUnscaledBudgetIntoNativeArmorAndAbsorption(GameTestHelper h) throws Exception {
        for (boolean enhanced : List.of(false, true)) try (var test = new Harness(h, enhanced)) {
            test.target.getAttribute(Attributes.ARMOR).setBaseValue(20);
            test.target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0)); test.target.setAbsorptionAmount(3);
            test.send("arm", 0); test.send("fire", 20); var receipt = test.receipts.getLast();
            double elemental = enhanced ? 1.55 : 1.5, overshield = enhanced ? 2.4 : 2.25;
            double spill = 20 - 3 / elemental - 4.5 / (.3 * overshield); double afterArmor = spill * (1 - Math.max(4, 20 - spill / 2) / 25);
            near(h, receipt.outgoing().orElseThrow().output().value(), 20, "global weapon damage excludes shield-only bonus");
            near(h, receipt.shieldLoss(), 7.5, "both shield capacities consumed"); near(h, receipt.shields().getLast().trace().remainingInput(), spill, "unscaled budget after local shield multipliers");
            near(h, receipt.absorptionLoss(), 3, "native absorption after armor"); near(h, receipt.healthLoss(), afterArmor - 3, "actual remaining native health loss");
            near(h, test.target.getHealth(), 100 - afterArmor + 3, "actual health");
            near(h, receipt.shields().getFirst().attackScaling().orElseThrow().output().value(), elemental, "first layer attack trace");
            near(h, receipt.shields().getLast().attackScaling().orElseThrow().output().value(), overshield, "second layer attack trace");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:layer_profile_snapshot", maxTicks = 10)
    public void detachedJsonAttackRetainsEnhancedBonusAndChoosesTheNewLayerAtImpact(GameTestHelper h) throws Exception {
        var test = new Harness(h, true);
        try {
            test.send("later", 20); test.runtime.start(SourceChange.remove(test.perk.instance())); test.runtime.start(SourceChange.remove(test.input.instance()));
            test.at(1, () -> {
                test.runtime.start(SourceChange.bind(test.input)); test.send("guardian", 0); test.runtime.start(SourceChange.remove(test.input.instance()));
                h.assertTrue(test.receipts.isEmpty(), "delayed attack has not fired");
            });
            test.at(2, () -> {
                try (test) {
                    h.assertValueEqual(test.receipts.size(), 1, "single detached hit"); var receipt = test.receipts.getFirst();
                    h.assertTrue(test.commands.getFirst().snapshot().orElseThrow().shieldScaling().isPresent(), "shield profile missing from snapshot");
                    near(h, receipt.shields().getFirst().attackScaling().orElseThrow().output().value(), 1.22, "captured enhanced modifier resolves newly granted Guardian layer");
                    near(h, receipt.healthLoss(), 20 - 4.5 / 1.22, "native delayed damage"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase public void rejectedHitsNeverConsumeLayersAndCooldownUsesUnscaledDamageBudget(GameTestHelper h) throws Exception {
        try (var test = new Harness(h, false)) {
            test.send("arm", 0); test.target.setPermanentlyInvulnerable(true); test.send("fire", 20);
            h.assertTrue(test.receipts.getLast().shields().isEmpty(), "invulnerable hit must not evaluate a shield layer"); near(h, test.capacity("elemental"), 3, "rejected hit keeps capacity");
            test.target.setPermanentlyInvulnerable(false); test.send("fire", 1); near(h, test.capacity("elemental"), 1.5, "local 1.5 multiplier");
            test.send("fire", .5); h.assertTrue(test.receipts.getLast().shields().isEmpty(), "cooldown rejection has no layer trace"); near(h, test.capacity("elemental"), 1.5, "cooldown rejects smaller hit");
            test.send("fire", 2); var receipt = test.receipts.getLast();
            h.assertValueEqual(receipt.shields().size(), 1, "only first layer was reached"); near(h, receipt.shields().getFirst().trace().input(), 1, "native cooldown admits 2 minus 1");
            near(h, test.capacity("elemental"), 0, "remaining first layer consumed"); near(h, test.capacity("overshield"), 4.5, "later layer unchanged"); near(h, test.target.getHealth(), 100, "no health overflow");
        }
        h.succeed();
    }
}
