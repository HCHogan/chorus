package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AbilityPreviousCostGameTest extends com.imdomestic.chorus.test.AbilityPreviousCostGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:previous_cost_chain", maxTicks = 60) @Override
    public void actualWorldEffectsConsumeOnlyTheIntermediateAbilityWithFinalCredit(GameTestHelper h) { super.actualWorldEffectsConsumeOnlyTheIntermediateAbilityWithFinalCredit(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:previous_cost_reject", maxTicks = 60) @Override
    public void missingOrExhaustedPreviousCostNeverFallsBackToAnotherLivePool(GameTestHelper h) { super.missingOrExhaustedPreviousCostNeverFallsBackToAnotherLivePool(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:previous_cost_refund", maxTicks = 60) @Override
    public void delayedNativeRefundKeepsItsPaidPoolAfterSelectionsAndOverridesChange(GameTestHelper h) { super.delayedNativeRefundKeepsItsPaidPoolAfterSelectionsAndOverridesChange(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:previous_cost_unknown", maxTicks = 60) @Override
    public void unknownNativeBenefitKeepsIntermediatePaymentAndActualHealthWithoutReplay(GameTestHelper h) { super.unknownNativeBenefitKeepsIntermediatePaymentAndActualHealthWithoutReplay(h); }
}
