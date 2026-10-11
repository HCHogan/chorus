package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;

public class NeoForgeHealingGameTest {
    @GameCase public void fractionResolvesBeforeLoaderMutationAndKeepsItsMaximumObservation(GameTestHelper h) {
        String tag = "chorus_fraction_modify_" + UUID.randomUUID();
        NeoForge.EVENT_BUS.addListener((LivingHealEvent event) -> {
            if (!event.getEntity().entityTags().contains(tag)) return;
            event.getEntity().removeTag(tag);
            event.getEntity().getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
            event.setAmount(3);
        });
        var target = FractionalHealingGameTest.target(h);
        try {
            target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100); target.addTag(tag);
            var command = FractionalHealingGameTest.fraction(target, .2);
            var first = MinecraftHealingExecutor.execute("fraction-modified", target, command);
            near(h, first.requested(), 20, "resolved before hook"); near(h, first.maximumHealth().orElseThrow(), 100, "pre-hook maximum");
            near(h, first.offered(), 3, "loader-modified amount"); near(h, first.effective(), 3, "actual write"); near(h, first.overheal(), 0, "reduction is not overheal");
            var next = MinecraftHealingExecutor.execute("fraction-next", target, command);
            near(h, next.requested(), 40, "next operation uses changed maximum"); near(h, target.getHealth(), 44, "two native writes");
        } finally { target.discard(); }
        h.succeed();
    }
    @GameCase public void nestedFractionalHealingHasSeparateMaximumSnapshotsAndActualLossBoundaries(GameTestHelper h) {
        String tag = "chorus_fraction_nested_" + UUID.randomUUID(); var children = new ArrayList<HealingReceipt>();
        NeoForge.EVENT_BUS.addListener((LivingHealEvent event) -> {
            var target = event.getEntity(); if (!target.entityTags().contains(tag)) return;
            target.removeTag(tag); target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
            children.add(MinecraftHealingExecutor.execute("fraction-child", target, FractionalHealingGameTest.fraction(target, .1)));
            target.heal(1);
        });
        var target = FractionalHealingGameTest.target(h);
        try {
            target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100); target.addTag(tag);
            var parent = MinecraftHealingExecutor.execute("fraction-parent", target, FractionalHealingGameTest.fraction(target, .25));
            near(h, parent.maximumHealth().orElseThrow(), 100, "parent snapshot"); near(h, parent.effective(), 25, "parent isolated write");
            h.assertValueEqual(children.size(), 1, "one child"); near(h, children.getFirst().maximumHealth().orElseThrow(), 200, "child snapshot");
            near(h, children.getFirst().effective(), 20, "child isolated write"); near(h, target.getHealth(), 47, "all actual writes");
        } finally { target.discard(); }
        h.succeed();
    }
    @GameCase public void cancelledMixedRecoveryDoesNotFallBackToTheLosingSource(GameTestHelper h) {
        String tag = "chorus_fraction_cancel_" + UUID.randomUUID(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        NeoForge.EVENT_BUS.addListener((LivingHealEvent event) -> {
            if (event.getEntity().entityTags().contains(tag)) { calls.incrementAndGet(); event.setCanceled(true); }
        });
        var target = FractionalHealingGameTest.target(h);
        try {
            target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100); target.addTag(tag);
            var p = FractionalHealingGameTest.program(); var offers = p.recoveryOffers(FractionalHealingGameTest.armed(p, target, 1_000_000, "fraction", "absolute"));
            var selection = Recovery.integrate(offers, 0, 50_000, List.of()).allocations().getFirst().command();
            var r = (Recovery.SelectionReceipt) FractionalHealingGameTest.world(h, target).apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), selection));
            h.assertValueEqual(r.outcome(), HealingReceipt.Outcome.REJECTED, "cancelled selected heal");
            h.assertValueEqual(r.selected().orElseThrow().offer().source().owner(), "giver/fraction", "retained winner");
            near(h, r.selected().orElseThrow().receipt().requested(), 1, "resolved cancelled request");
            h.assertValueEqual(calls.get(), 1, "no fallback source execution"); near(h, target.getHealth(), 1, "cancelled recovery leaves HP");
        } finally { target.discard(); }
        h.succeed();
    }
    @GameCase public void loaderReductionIsNotReportedAsOverheal(GameTestHelper helper) {
        String tag = "chorus_heal_reduced_" + UUID.randomUUID();
        NeoForge.EVENT_BUS.addListener((LivingHealEvent event) -> { if (event.getEntity().entityTags().contains(tag)) event.setAmount(2); });
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(4); target.addTag(tag);
        var result = MinecraftHealingExecutor.execute("reduced-heal", target, command(target, 8));
        near(helper, result.requested(), 8, "original request"); near(helper, result.offered(), 2, "loader amount");
        near(helper, result.effective(), 2, "actual healing"); near(helper, result.overheal(), 0, "no false overheal from reduction");
        near(helper, target.getHealth(), 6, "actual health"); helper.succeed();
    }
    @GameCase public void loaderAmplificationUsesModifiedAmountForCapacityOverflow(GameTestHelper helper) {
        String tag = "chorus_heal_boost_" + UUID.randomUUID();
        NeoForge.EVENT_BUS.addListener((LivingHealEvent event) -> { if (event.getEntity().entityTags().contains(tag)) event.setAmount(8); });
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(9); target.addTag(tag);
        var result = MinecraftHealingExecutor.execute("boosted-heal", target, command(target, 2));
        near(helper, result.requested(), 2, "original request"); near(helper, result.offered(), 8, "boosted amount");
        near(helper, result.effective(), 1, "actual healing"); near(helper, result.overheal(), 7, "boosted capacity overflow");
        helper.succeed();
    }
    @GameCase public void cancelledHealingHasNeitherHealthGainNorOverheal(GameTestHelper helper) {
        String tag = "chorus_heal_cancel_" + UUID.randomUUID();
        NeoForge.EVENT_BUS.addListener((LivingHealEvent event) -> { if (event.getEntity().entityTags().contains(tag)) event.setCanceled(true); });
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(9); target.addTag(tag);
        var result = MinecraftHealingExecutor.execute("cancelled-heal", target, command(target, 8));
        helper.assertValueEqual(result.outcome(), HealingReceipt.Outcome.REJECTED, "cancelled outcome");
        near(helper, result.offered(), 0, "no admitted healing"); near(helper, result.overheal(), 0, "cancelled is not overheal");
        near(helper, target.getHealth(), 9, "cancelled health"); helper.assertTrue(HealingFacts.from(result).isEmpty(), "Cancelled heal published facts");
        helper.succeed();
    }
    @GameCase public void nestedExplicitAndNativeHealingAreExcludedFromParentReceipt(GameTestHelper helper) {
        String tag = "chorus_heal_nested_" + UUID.randomUUID(); var children = new ArrayList<HealingReceipt>();
        NeoForge.EVENT_BUS.addListener((LivingHealEvent event) -> {
            var target = event.getEntity();
            if (target.entityTags().contains(tag)) {
                target.removeTag(tag);
                children.add(MinecraftHealingExecutor.execute("child-heal", target, command(target, 3)));
                target.heal(1);
            }
        });
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(2); target.addTag(tag);
        var parent = MinecraftHealingExecutor.execute("parent-heal", target, command(target, 4));
        helper.assertValueEqual(children.size(), 1, "one explicit child");
        near(helper, children.getFirst().effective(), 3, "child healing"); near(helper, parent.effective(), 4, "parent excludes both children");
        near(helper, parent.overheal(), 0, "parent capacity after child writes"); near(helper, target.getHealth(), 10, "total native health");
        helper.succeed();
    }
    @GameCase public void damageInsideHealingHookDoesNotReduceReportedHealing(GameTestHelper helper) {
        String tag = "chorus_heal_hurt_" + UUID.randomUUID(); var damage = new ArrayList<DamageReceipt>();
        NeoForge.EVENT_BUS.addListener((LivingHealEvent event) -> {
            var target = event.getEntity();
            if (target.entityTags().contains(tag)) {
                target.removeTag(tag); damage.add(MinecraftDamageExecutor.execute("nested-hurt", target,
                        ((net.minecraft.server.level.ServerLevel) target.level()).damageSources().generic(), 2, false));
            }
        });
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(6); target.addTag(tag);
        var heal = MinecraftHealingExecutor.execute("healing-after-hurt", target, command(target, 3));
        near(helper, damage.getFirst().healthLoss(), 2, "nested damage"); near(helper, heal.effective(), 3, "healing is not the net health delta");
        near(helper, target.getHealth(), 7, "net health"); helper.succeed();
    }
    @GameCase public void throwingHealingHookClosesScopesAndUnknownOperationDoesNotRetry(GameTestHelper helper) {
        String tag = "chorus_heal_fail_" + UUID.randomUUID();
        NeoForge.EVENT_BUS.addListener((LivingHealEvent event) -> {
            if (event.getEntity().entityTags().contains(tag)) { event.getEntity().removeTag(tag); throw new IllegalStateException("expected healing hook failure"); }
        });
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(2); target.addTag(tag);
        var ledger = new OperationLedger(); var request = new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), command(target, 3));
        boolean threw = false;
        try { ledger.execute(request, command -> MinecraftHealingExecutor.execute("unknown-heal", target, (HealingCommand) command)); }
        catch (IllegalStateException expected) { threw = true; }
        helper.assertTrue(threw, "Native failure was swallowed");
        threw = false;
        try { ledger.execute(request, _ -> { throw new AssertionError("Unknown healing was retried"); }); }
        catch (IllegalStateException expected) { threw = true; }
        helper.assertTrue(threw, "Unknown healing did not remain pending"); near(helper, target.getHealth(), 2, "no unexpected healing");
        var next = MinecraftHealingExecutor.execute("new-independent-heal", target, command(target, 3));
        near(helper, next.effective(), 3, "scopes cleared after failure"); near(helper, target.getHealth(), 5, "independent later healing");
        helper.succeed();
    }
}
