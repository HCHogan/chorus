package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class BoxBreathingGameTest extends com.imdomestic.chorus.test.BoxBreathingGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:box_breathing_normal",maxTicks=50) @Override
    public void NormalLastRoundKeepsItsCapturedBonusAfterRelease(GameTestHelper h)throws Exception {super.NormalLastRoundKeepsItsCapturedBonusAfterRelease(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:box_breathing_enhanced",maxTicks=50) @Override
    public void EnhancedLastRoundStartsOneTenthOfASecondEarlier(GameTestHelper h)throws Exception {super.EnhancedLastRoundStartsOneTenthOfASecondEarlier(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:box_breathing_rearm",maxTicks=100) @Override
    public void EarlyPhysicalFireRestartsTheWaitAndBodyFireConsumesTheNextOpportunity(GameTestHelper h)throws Exception {super.EarlyPhysicalFireRestartsTheWaitAndBodyFireConsumesTheNextOpportunity(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:box_breathing_shield",maxTicks=50) @Override
    public void ShieldSuppressesPrecisionFactorButRetainsNaturalWeakspotBonus(GameTestHelper h)throws Exception {super.ShieldSuppressesPrecisionFactorButRetainsNaturalWeakspotBonus(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:box_breathing_glaive",maxTicks=50) @Override
    public void GlaiveNaturalWeakspotGetsThirtyPercentWithoutAPrecisionMultiplier(GameTestHelper h)throws Exception {super.GlaiveNaturalWeakspotGetsThirtyPercentWithoutAPrecisionMultiplier(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:box_breathing_bubble",maxTicks=50) @Override
    public void SyntheticPrecisionRegionDoesNotInheritNaturalWeakspotDamage(GameTestHelper h)throws Exception {super.SyntheticPrecisionRegionDoesNotInheritNaturalWeakspotDamage(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:box_breathing_restore",maxTicks=50) @Override
    public void WaitingPerkResumesOnItsOriginalDeadlineAndReleaseStillClearsIt(GameTestHelper h)throws Exception {super.WaitingPerkResumesOnItsOriginalDeadlineAndReleaseStillClearsIt(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:box_breathing_unknown",maxTicks=50) @Override
    public void UnknownHitAfterConsumptionPreservesHealthLossAndDoesNotReplayTheShot(GameTestHelper h)throws Exception {super.UnknownHitAfterConsumptionPreservesHealthLossAndDoesNotReplayTheShot(h);}
}
