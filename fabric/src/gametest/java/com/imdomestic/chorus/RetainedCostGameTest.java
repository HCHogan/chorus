package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class RetainedCostGameTest extends com.imdomestic.chorus.test.RetainedCostGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:retained_cost_shared", maxTicks = 30) @Override
    public void realAbilityProjectileAndDelayedRefundSharePaidBudgetAfterSelectionIsCleared(GameTestHelper h) throws Exception { super.realAbilityProjectileAndDelayedRefundSharePaidBudgetAfterSelectionIsCleared(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:retained_cost_free", maxTicks = 30) @Override
    public void freeAbilityCannotManufactureEnergyOrHealingOnPhysicalAndDelayedCallbacks(GameTestHelper h) throws Exception { super.freeAbilityCannotManufactureEnergyOrHealingOnPhysicalAndDelayedCallbacks(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:retained_cost_expired", maxTicks = 30) @Override
    public void expiryBeforePhysicalImpactRevokesCapturedRightWithoutFailingFlight(GameTestHelper h) throws Exception { super.expiryBeforePhysicalImpactRevokesCapturedRightWithoutFailingFlight(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:retained_cost_unknown", maxTicks = 30) @Override
    public void unknownHealingReceiptPreservesRefundAndActualHealingWithoutReplaying(GameTestHelper h) throws Exception { super.unknownHealingReceiptPreservesRefundAndActualHealingWithoutReplaying(h); }
}
