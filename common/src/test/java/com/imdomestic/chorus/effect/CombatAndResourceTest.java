package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.combat.ShieldPlan;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.resource.Resources;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CombatAndResourceTest {
    private static final double EPS = 1e-10;
    private static ResourceState energy(double value, double capacity) {
        return new ResourceState(new ResourceState.Key("player", "grenade"), value, capacity, 0);
    }

    @Test void layeredShieldConsumesBudgetRatherThanSubtractingLossFromInput() {
        var layers = List.of(new ShieldPlan.Layer("overshield", 45, .3), new ShieldPlan.Layer("shield", 100, 1));
        var plan = ShieldPlan.calculate(200, layers);
        assertEquals(95, plan.shieldLoss(), EPS);
        assertEquals(150, plan.hits().getFirst().spentInput(), EPS);
        assertEquals(50, plan.after().get(1).capacity(), EPS);
        assertEquals(0, plan.toVanilla(), EPS);
        assertEquals(45, layers.getFirst().capacity());
    }

    @Test void leftoverGoesToVanillaAndImmuneLayerBlocksWithoutDivision() {
        var plan = ShieldPlan.calculate(200, List.of(new ShieldPlan.Layer("shield", 45, .3)));
        assertEquals(50, plan.toVanilla(), EPS);
        var blocked = ShieldPlan.calculate(200, List.of(new ShieldPlan.Layer("immune", 1, 0), new ShieldPlan.Layer("later", 40, 1)));
        assertTrue(blocked.blocked());
        assertEquals(0, blocked.toVanilla());
        assertEquals(0, blocked.shieldLoss());
        assertEquals(200, blocked.remainingInput());
        assertEquals(40, blocked.after().get(1).capacity());
    }

    @Test void shieldBudgetIsConservedAcrossVariedInputsAndMultipliers() {
        for (double input : List.of(0.0, .01, 1.0, 45.0, 200.0, 1000.0)) {
            for (double multiplier : List.of(.01, .3, 1.0, 2.0, 100.0)) {
                var plan = ShieldPlan.calculate(input, List.of(new ShieldPlan.Layer("first", 45, multiplier), new ShieldPlan.Layer("second", 50, .5)));
                assertEquals(input, plan.hits().stream().mapToDouble(ShieldPlan.Hit::spentInput).sum() + plan.toVanilla(), EPS);
                for (int i = 0; i < plan.after().size(); i++) {
                    assertTrue(plan.after().get(i).capacity() >= 0);
                    assertTrue(plan.after().get(i).capacity() <= plan.before().get(i).capacity());
                }
            }
        }
    }

    @Test void preventedDeathHasDamageButNoKillAndCancelledDamageHasNoCommittedLoss() {
        var totem = new DamageReceipt("hit", DamageReceipt.Outcome.APPLIED, 0, 4, 6, Optional.empty(), true);
        assertFalse(totem.lethal());
        assertEquals(6, totem.effective(false));
        assertEquals(10, totem.effective(true));
        assertThrows(IllegalArgumentException.class, () -> new DamageReceipt("hit", DamageReceipt.Outcome.CANCELLED,
                1, 0, 0, Optional.empty(), false));
        assertThrows(IllegalArgumentException.class, () -> new DamageReceipt("hit", DamageReceipt.Outcome.APPLIED,
                0, 0, 6, Optional.of("death"), true));
    }

    @Test void tenPercentGrantDoesNotDoubleWithTwoCharges() {
        var result = Resources.grant(energy(0, 2), .1, .1);
        assertEquals(.1, result.after().value(), EPS);
        var full = Resources.grant(energy(1.95, 2), .1, .1);
        assertEquals(.05, full.credited(), EPS);
        assertEquals(.05, full.overflow(), EPS);
    }

    @Test void freeAndFailedCastsCannotGenerateRefundEnergy() {
        var failed = Resources.spend(energy(.2, 1), "cast", 1);
        assertFalse(failed.succeeded());
        assertEquals(.2, Resources.refund(failed.after(), failed.receipt(), 1).grant().after().value(), EPS);
        var free = Resources.spend(energy(.2, 1), "free", 0);
        assertEquals(.2, Resources.refund(free.after(), free.receipt(), 1).grant().after().value(), EPS);
    }

    @Test void refundCannotExceedPaidCostEvenWhenEarlierRefundOverflows() {
        var spend = Resources.spend(energy(1, 1), "cast", 1);
        var first = Resources.refund(energy(1, 1), spend.receipt(), .7);
        assertEquals(.7, first.grant().overflow(), EPS);
        var second = Resources.refund(energy(0, 1), first.receipt(), .7);
        assertEquals(.3, second.grant().credited(), EPS);
        assertEquals(0, Resources.refund(second.grant().after(), second.receipt(), 1).grant().credited(), EPS);
        var other = new ResourceState(new ResourceState.Key("other player", "grenade"), 0, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> Resources.refund(other, spend.receipt(), 1));
    }

    @Test void regenerationSplitsAtExpiryAndDoesNotBankOverflow() {
        var result = Resources.integrate(energy(0, 1), 1_000_000, .4, List.of(new Resources.RateChange(500_000, .1)));
        assertEquals(.25, result.value(), EPS);
        var overfilledThenDrained = Resources.integrate(energy(.9, 1), 2_000_000, 1,
                List.of(new Resources.RateChange(1_000_000, -.5)));
        assertEquals(.5, overfilledThenDrained.value(), EPS);
        assertThrows(IllegalArgumentException.class, () -> Resources.integrate(result, 0, 0, List.of()));
    }
}
