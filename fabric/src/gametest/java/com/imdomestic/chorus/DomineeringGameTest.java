package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DomineeringGameTest extends com.imdomestic.chorus.test.DomineeringGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:domineering_paid", maxTicks=20) @Override
    public void paidPhysicalHitSuppressesAndRefillsThenActualKillCreatesCollectableBreach(GameTestHelper h) throws Exception { super.paidPhysicalHitSuppressesAndRefillsThenActualKillCreatesCollectableBreach(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:domineering_applicants", maxTicks=20) @Override
    public void reapplicantGetsBenefitsWhileSharedSuppressionKeepsTheFirstCastersOrigin(GameTestHelper h) throws Exception { super.reapplicantGetsBenefitsWhileSharedSuppressionKeepsTheFirstCastersOrigin(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:domineering_rejected", maxTicks=20) @Override
    public void rejectedDeadMissingAndPureStatusGrantsDoNotAwardFragmentBenefits(GameTestHelper h) throws Exception { super.rejectedDeadMissingAndPureStatusGrantsDoNotAwardFragmentBenefits(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:domineering_time", maxTicks=310) @Override
    public void realFourSecondCooldownAllowsAnotherRefillAndResetsTenSecondBenefit(GameTestHelper h) throws Exception { super.realFourSecondCooldownAllowsAnotherRefillAndResetsTenSecondBenefit(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:domineering_history", maxTicks=20) @Override
    public void historicalSuppressionAndDeathPositionSurviveCorpseRemovalAndForeignCaster(GameTestHelper h) throws Exception { super.historicalSuppressionAndDeathPositionSurviveCorpseRemovalAndForeignCaster(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:domineering_live", maxTicks=20) @Override
    public void currentFragmentAtImpactControlsActivationAndEmptyHandsStillGetTimedBenefits(GameTestHelper h) throws Exception { super.currentFragmentAtImpactControlsActivationAndEmptyHandsStillGetTimedBenefits(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:domineering_unknown", maxTicks=20) @Override
    public void unknownResultAfterPhysicalBreachCreationKeepsPriorBenefitsAndDoesNotSpawnAgain(GameTestHelper h) throws Exception { super.unknownResultAfterPhysicalBreachCreationKeepsPriorBenefitsAndDoesNotSpawnAgain(h); }
}
