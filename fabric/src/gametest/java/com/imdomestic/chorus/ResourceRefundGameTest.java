package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ResourceRefundGameTest extends com.imdomestic.chorus.test.ResourceRefundGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void overflowedRefundCannotBeReclaimedAfterWorldActionAndAnotherCost(GameTestHelper helper) throws Exception { super.overflowedRefundCannotBeReclaimedAfterWorldActionAndAnotherCost(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void failedAndFreePaymentsCannotCreateRefundEnergyInWorldSequence(GameTestHelper helper) throws Exception { super.failedAndFreePaymentsCannotCreateRefundEnergyInWorldSequence(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void fullChargePreservesPartialProgressAndUsesClippedGainForWorldAction(GameTestHelper helper) throws Exception { super.fullChargePreservesPartialProgressAndUsesClippedGainForWorldAction(helper); }
}
