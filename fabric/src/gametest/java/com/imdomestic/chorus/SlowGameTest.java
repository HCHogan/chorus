package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SlowGameTest extends com.imdomestic.chorus.test.SlowGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:slow_attributes") @Override
    public void actualGuardianAndCombatantAttributesJumpImpulseAndMovementAbilityHaveSeparatePenalties(GameTestHelper h){super.actualGuardianAndCombatantAttributesJumpImpulseAndMovementAbilityHaveSeparatePenalties(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:slow_conversion") @Override
    public void successfulHundredStackConversionRemovesSlowAndThawNeverRestoresConsumedStacks(GameTestHelper h){super.successfulHundredStackConversionRemovesSlowAndThawNeverRestoresConsumedStacks(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:slow_rejection") @Override
    public void deniedFreezeRetainsCommittedSlowAndCanRetryAfterHostAuthorizationChanges(GameTestHelper h){super.deniedFreezeRetainsCommittedSlowAndCanRetryAfterHostAuthorizationChanges(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:slow_expiry",maxTicks=65) @Override
    public void shorterRefreshKeepsOriginalExpiryAndAnotherCasterOwnsThresholdConversion(GameTestHelper h){super.shorterRefreshKeepsOriginalExpiryAndAnotherCasterOwnsThresholdConversion(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:slow_amplified") @Override
    public void slowMultipliesAmplifiedSpeedAndCleansingPreservesTheIndependentArcContribution(GameTestHelper h){super.slowMultipliesAmplifiedSpeedAndCleansingPreservesTheIndependentArcContribution(h);}
}
