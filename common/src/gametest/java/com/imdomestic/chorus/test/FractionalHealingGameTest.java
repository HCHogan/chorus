package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class FractionalHealingGameTest {
    static CompiledEffects program() { return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("fractional_healing")).getOrThrow(); }
    public static HealingCommand fraction(LivingEntity target, double amount) {
        var fixed = HealingGameTest.command(target, amount);
        return new HealingCommand(fixed.target(), fixed.source(), amount, fixed.tags(), HealingCommand.Basis.MAX_HEALTH);
    }
    static MinecraftWorldActions world(GameTestHelper h, LivingEntity target) {
        return new MinecraftWorldActions(h.getLevel(), id -> id.equals(target.getUUID().toString()) ? target : null,
                _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
    }
    static EffectState armed(CompiledEffects p, LivingEntity target, long duration, String... buffs) {
        var store = BuffStore.empty(); String id = target.getUUID().toString();
        for (String buff : buffs) store = Buffs.grant(store, p.buff("test:" + buff), id, id,
                new BuffInstance.Origin("giver/" + buff, buff, "", ""), 1, 1, duration).store();
        return EffectState.empty().withBuffs(store);
    }
    static void step(EffectSession session, long time) { session.start(time, new RuleEngine.Signal("test:noop", RuleEngine.Empty.INSTANCE)); }
    static LivingEntity target(GameTestHelper h) {
        var target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setNoGravity(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40); target.setHealth(1); return target;
    }
    @GameCase public void percentageSamplesCurrentMaximumAndReportsResolvedRequestActualHealingAndOverflow(GameTestHelper h) {
        for (boolean player : List.of(false, true)) {
            var target = player ? NativeMeleeGameTest.player(h) : target(h);
            try {
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40); target.setHealth(1);
                var command = fraction(target, .2); target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
                var first = MinecraftHealingExecutor.execute("fraction-current", target, command);
                near(h, first.requested(), 20, "current maximum, not construction-time maximum"); near(h, first.effective(), 20, "actual HP");
                near(h, first.maximumHealth().orElseThrow(), 100, "recorded maximum"); near(h, target.getHealth(), 21, "target HP");
                target.setHealth(98); var cap = MinecraftHealingExecutor.execute("fraction-cap", target, command);
                near(h, cap.effective(), 2, "capped actual gain"); near(h, cap.overheal(), 18, "actual overflow");
                var zero = MinecraftHealingExecutor.execute("fraction-zero", target, fraction(target, 0));
                h.assertTrue(zero.amountResolved() && !zero.applied(), "known zero resolution"); near(h, zero.requested(), 0, "known zero requested");
            } finally { target.discard(); }
        }
        h.succeed();
    }
    @GameCase public void absentTargetsKeepFractionalAmountUnresolvedAndDeadTargetsNeverRevive(GameTestHelper h) {
        var target = target(h); var world = world(h, target);
        try {
            var missing = new HealingCommand("missing", fraction(target, .2).source(), .2, Set.of(), HealingCommand.Basis.MAX_HEALTH);
            var r = (HealingReceipt) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), missing));
            h.assertValueEqual(r.outcome(), HealingReceipt.Outcome.MISSING, "missing target"); h.assertTrue(!r.amountResolved(), "missing maximum fabricated");
            try { r.requested(); throw new AssertionError("unobserved maximum treated as zero"); } catch (IllegalArgumentException expected) { }
            target.setHealth(0); var dead = MinecraftHealingExecutor.execute("dead", target, fraction(target, .2));
            h.assertValueEqual(dead.outcome(), HealingReceipt.Outcome.DEAD, "dead target"); near(h, target.getHealth(), 0, "no revival");
        } finally { target.discard(); }
        h.succeed();
    }
    @GameCase public void mixedRecoverySelectsByResolvedRateAndRetainsWinningSourceAcrossMaximumChangesAndExpiry(GameTestHelper h) {
        var target = target(h); var p = program(); var world = world(h, target); var receipts = new ArrayList<Recovery.SelectionReceipt>();
        try {
            var session = new EffectSession(p.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1), armed(p, target, 120_000, "fraction", "absolute"), request -> {
                var receipt = (Recovery.SelectionReceipt) world.apply(request); receipts.add(receipt); return receipt;
            });
            step(session, 50_000); near(h, target.getHealth(), 1.5, "10 HP/s beats 20% of 40");
            h.assertValueEqual(receipts.getFirst().selected().orElseThrow().offer().source().owner(), "giver/absolute", "first winner credit");
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); step(session, 200_000);
            h.assertValueEqual(receipts.size(), 3, "two grid intervals and final fractional interval");
            near(h, target.getHealth(), 2.9, "20% of 100 beats 10 HP/s");
            for (var r : receipts.subList(1, receipts.size())) {
                var selected = r.selected().orElseThrow(); h.assertValueEqual(selected.offer().source().owner(), "giver/fraction", "new winner credit");
                near(h, selected.maximumHealth(), 100, "one observed maximum");
                var event = (EffectEvent) HealingFacts.from(selected.receipt()).getFirst().payload();
                h.assertValueEqual(event.source().owner(), "giver/fraction", "fact credit");
            }
            h.assertTrue(session.state().engine().domain().buffs().instances().isEmpty(), "expired candidates remained active");
            step(session, 500_000); h.assertValueEqual(receipts.size(), 3, "expiry prevents extra healing");
        } finally { target.discard(); }
        h.succeed();
    }
    @GameCase public void operationLedgerReusesOriginalMaximumAndUnknownHealingNeverReexecutes(GameTestHelper h) {
        var target = target(h); var p = program(); var world = world(h, target);
        try {
            var offers = p.recoveryOffers(armed(p, target, 1_000_000, "fraction", "absolute"));
            var command = Recovery.integrate(offers, 0, 50_000, List.of()).allocations().getFirst().command();
            var request = new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), command); var ledger = new OperationLedger();
            var first = ledger.execute(request, _ -> world.apply(request)); target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            var replay = ledger.execute(request, _ -> { throw new AssertionError("repeated recovery reselected source"); });
            h.assertValueEqual(replay, first, "same operation receipt"); near(h, target.getHealth(), 1.5, "no duplicate healing");
            var unknown = new RuleEngine.WorldRequest(new RuleEngine.OperationId(2, 0, 0), command);
            try { ledger.execute(unknown, _ -> { world.apply(unknown); throw new IllegalStateException("unknown after real fractional healing"); }); throw new AssertionError("missing failure"); }
            catch (IllegalStateException expected) { h.assertTrue(expected.getMessage().contains("unknown after"), "unexpected failure"); }
            near(h, target.getHealth(), 2.5, "committed unknown healing remains");
            try { ledger.execute(unknown, _ -> { throw new AssertionError("unknown recovery replayed"); }); throw new AssertionError("unknown was retried"); }
            catch (IllegalStateException expected) { }
            near(h, target.getHealth(), 2.5, "no second world write");
        } finally { target.discard(); }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:fraction_recovery", maxTicks = 25)
    public void actualTicksPreserveRegenerationOnShieldOnlyDamageAndRestartAfterHealthDamage(GameTestHelper h) {
        var target = target(h); target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.setHealth(10);
        var p = program(); var world = world(h, target);
        var runtime = MinecraftEffectRuntime.install(h.getLevel(), p, armed(p, target, BuffDefinition.FOREVER, "fraction", "shield"),
                new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world, MinecraftEffectRuntime::nativeSource);
        try {
            var first = MinecraftDamageExecutor.execute("shield-only", target, h.getLevel().damageSources().generic(), 4, false);
            near(h, first.shieldLoss(), 4, "shield-only input"); near(h, first.healthLoss(), 0, "no HP damage");
            h.runAfterDelay(2, () -> {
                try {
                    runtime.prepare(); h.assertTrue(target.getHealth() > 10, "shield-only damage stopped regeneration");
                    target.damageCooldownTime = 0;
                    var damage = MinecraftDamageExecutor.execute("hp-damage", target, h.getLevel().damageSources().generic(), 8, false);
                    near(h, damage.shieldLoss(), 6, "remaining shield"); near(h, damage.healthLoss(), 2, "HP damage");
                    near(h, ready(runtime), 0, "health damage pauses recovery");
                    double before = target.getHealth();
                    h.runAfterDelay(2, () -> { try { runtime.prepare(); near(h, target.getHealth(), before, "delay has no recovery"); } catch (Throwable e) { runtime.close(); target.discard(); throw e; } });
                    h.runAfterDelay(7, () -> { try { runtime.prepare(); h.assertTrue(target.getHealth() > before && ready(runtime) == 1, "delay failed to resume regeneration"); h.assertTrue(runtime.failure().isEmpty(), "recovery runtime failed"); h.succeed(); } finally { runtime.close(); target.discard(); } });
                } catch (Throwable e) { runtime.close(); target.discard(); throw e; }
            });
        } catch (Throwable e) { runtime.close(); target.discard(); throw e; }
    }
    static double ready(MinecraftEffectRuntime runtime) {
        return runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:fraction")).findFirst().orElseThrow().components().numbers().get("ready");
    }
}
