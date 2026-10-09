package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.*;
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

public class HealingGameTest {
    public static HealingCommand command(LivingEntity target, double amount) {
        return new HealingCommand(target.getUUID().toString(), new BuffInstance.Origin(target.getUUID().toString(), "test:healing", "", ""), amount, Set.of("test:healing"));
    }
    public static void near(GameTestHelper helper, double actual, double expected, String field) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-4, field + ": expected " + expected + ", got " + actual);
    }
    private static CompiledEffects program() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(HealingGameTest.class.getResourceAsStream("/effects/healing.json")), StandardCharsets.UTF_8)) {
            return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
        }
    }
    private static EffectSource source(LivingEntity actor) {
        return new EffectSource("healing", "test:healing", actor.getUUID().toString(), command(actor, 1).source(), Set.of());
    }
    private static EffectEvent input(EffectSource source, LivingEntity target, double amount) {
        return new EffectEvent(source.holder(), target.getUUID().toString(), source.origin(), Set.of(), Map.of("amount", new Measure(amount, Unit.DAMAGE)));
    }
    @GameCase public void healthCapAndFullHealthPreserveActualAndOverheal(GameTestHelper helper) {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(target.getMaxHealth() - 2);
        var first = MinecraftHealingExecutor.execute("heal-cap", target, command(target, 8));
        near(helper, first.requested(), 8, "requested"); near(helper, first.offered(), 8, "offered");
        near(helper, first.effective(), 2, "effective"); near(helper, first.overheal(), 6, "overheal");
        near(helper, target.getHealth(), target.getMaxHealth(), "capped native health");
        var full = MinecraftHealingExecutor.execute("heal-full", target, command(target, 8));
        near(helper, full.effective(), 0, "full effective"); near(helper, full.overheal(), 8, "full overheal");
        helper.assertValueEqual(HealingFacts.from(full).stream().map(RuleEngine.Signal::type).toList(), List.of("chorus:heal", "chorus:overheal"), "full-health facts");
        var zero = MinecraftHealingExecutor.execute("heal-zero", target, command(target, 0));
        helper.assertTrue(HealingFacts.from(zero).isEmpty(), "Zero healing created facts");
        helper.succeed();
    }
    @GameCase public void jsonDamageResultHealsOnlyActualHealthLoss(GameTestHelper helper) throws Exception {
        var actor = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); actor.setHealth(4);
        var victim = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2); victim.getAttribute(Attributes.ARMOR).setBaseValue(20);
        victim.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0)); victim.setAbsorptionAmount(3);
        var source = source(actor); var receipts = new ArrayList<RuleEngine.ActionResult>();
        var world = new MinecraftWorldActions(helper.getLevel(), id -> id.equals(actor.getUUID().toString()) ? actor : victim,
                _ -> helper.getLevel().damageSources().mobAttack(actor), (_, _) -> true, _ -> {});
        try (var runtime = MinecraftEffectRuntime.install(helper.getLevel(), program(), EffectState.empty().withSource(source),
                new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                    var receipt = world.apply(request); receipts.add(receipt); return receipt;
                }, MinecraftEffectRuntime::nativeSource)) {
            runtime.start(new RuleEngine.Signal("test:drain", input(source, victim, 0)));
            helper.assertValueEqual(receipts.size(), 2, "damage then heal");
            near(helper, ((DamageReceipt) receipts.getFirst()).healthLoss(), 1, "actual damage HP");
            var heal = (HealingReceipt) receipts.getLast(); near(helper, heal.requested(), 1, "dependent healing request");
            near(helper, heal.effective(), 1, "dependent actual healing"); near(helper, actor.getHealth(), 5, "actor health");
            var meter = runtime.state().engine().domain().buffs().instances().values().iterator().next();
            near(helper, meter.components().numbers().get("effective"), 1, "result binding");
            near(helper, meter.components().numbers().get("observed"), 1, "one healing fact processed after the action");
            helper.assertTrue(runtime.failure().isEmpty(), "Damage-to-heal runtime failed");
        }
        helper.succeed();
    }
    @GameCase public void missingRemovedAndDeadTargetsAreNotHealed(GameTestHelper helper) {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
        var world = new MinecraftWorldActions(helper.getLevel(), id -> id.equals(target.getUUID().toString()) ? target : null,
                _ -> helper.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
        var missing = new HealingCommand("missing", command(target, 1).source(), 4, Set.of());
        var result = (HealingReceipt) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), missing));
        helper.assertValueEqual(result.outcome(), HealingReceipt.Outcome.MISSING, "missing target");
        target.setHealth(0);
        var dead = MinecraftHealingExecutor.execute("dead", target, command(target, 8));
        helper.assertValueEqual(dead.outcome(), HealingReceipt.Outcome.DEAD, "dead target"); near(helper, target.getHealth(), 0, "no revival");
        target.discard(); var removed = MinecraftHealingExecutor.execute("removed", target, command(target, 8));
        helper.assertValueEqual(removed.outcome(), HealingReceipt.Outcome.MISSING, "removed target");
        helper.succeed();
    }
    @GameCase public void playerHealingUsesMaxHealthAndLeavesAbsorptionUntouched(GameTestHelper helper) {
        var target = helper.makeMockServerPlayerInLevel(); target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40); target.setHealth(35);
        target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0)); target.setAbsorptionAmount(3);
        var result = MinecraftHealingExecutor.execute("player-heal", target, command(target, 8));
        near(helper, result.effective(), 5, "player healing"); near(helper, result.overheal(), 3, "player overheal");
        near(helper, target.getHealth(), 40, "player maximum"); near(helper, target.getAbsorptionAmount(), 3, "absorption unchanged");
        helper.succeed();
    }
    @GameCase public void duplicateWorldOperationReturnsReceiptWithoutHealingTwice(GameTestHelper helper) {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(2);
        var request = new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), command(target, 3)); var ledger = new OperationLedger();
        var first = ledger.execute(request, command -> MinecraftHealingExecutor.execute("unique-heal", target, (HealingCommand) command));
        var repeat = ledger.execute(request, _ -> { throw new AssertionError("Duplicate healing executed"); });
        helper.assertValueEqual(first, repeat, "identical cached healing result"); near(helper, target.getHealth(), 5, "single healing");
        helper.succeed();
    }
    @GameCase public void logicalPeriodicSignalsCanExecuteRealHealing(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(2);
        var source = source(target); var state = EffectState.empty().withSource(source).schedule(new EffectState.Timer("healing-period",
                70_001, 70_001, 3, new RuleEngine.Signal("test:heal", input(source, target, 1)), Optional.empty()));
        var world = new MinecraftWorldActions(helper.getLevel(), _ -> target, _ -> helper.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
        var receipts = new ArrayList<HealingReceipt>();
        var session = new EffectSession(program().engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1), state, request -> {
            var receipt = (HealingReceipt) world.apply(request); receipts.add(receipt); return receipt;
        });
        session.start(210_003, new RuleEngine.Signal("test:finish", RuleEngine.Empty.INSTANCE));
        helper.assertValueEqual(receipts.size(), 3, "three logical periods"); near(helper, target.getHealth(), 5, "three actual heals");
        helper.assertTrue(session.state().engine().domain().timers().isEmpty(), "Periodic timer did not finish");
        near(helper, session.state().engine().domain().buffs().instances().values().iterator().next().components().numbers().get("observed"), 3, "periodic healing facts");
        helper.succeed();
    }
}
