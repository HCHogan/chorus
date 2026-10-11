package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SnapshotSightsGameTest extends com.imdomestic.chorus.test.SnapshotSightsGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:snapshot_normal",maxTicks=30) @Override
    public void NormalPrimaryUsesThePublishedCurveAndOnlyTheEnterScalar(GameTestHelper h)throws Exception {super.NormalPrimaryUsesThePublishedCurveAndOnlyTheEnterScalar(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:snapshot_enhanced",maxTicks=30) @Override
    public void EnhancedPrimaryKeepsAdsTimeAndAddsFiveStability(GameTestHelper h)throws Exception {super.EnhancedPrimaryKeepsAdsTimeAndAddsFiveStability(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:snapshot_special",maxTicks=30) @Override
    public void SpecialWeaponUsesPointEightWithItsOwnSniperCurve(GameTestHelper h)throws Exception {super.SpecialWeaponUsesPointEightWithItsOwnSniperCurve(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:snapshot_power",maxTicks=35) @Override
    public void PowerParameterIsExplicitAndMissingCalibrationCannotTransferOwnership(GameTestHelper h)throws Exception {super.PowerParameterIsExplicitAndMissingCalibrationCannotTransferOwnership(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:snapshot_stacking",maxTicks=35) @Override
    public void ChargedSurplusAndAmplifiedComposeWhileArmorWeaponsLeavesAdsUnchanged(GameTestHelper h)throws Exception {super.ChargedSurplusAndAmplifiedComposeWhileArmorWeaponsLeavesAdsUnchanged(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:snapshot_restore",maxTicks=30) @Override
    public void RestoredStatPointPipelineFinishesAtItsOriginalMicrosecondDeadline(GameTestHelper h)throws Exception {super.RestoredStatPointPipelineFinishesAtItsOriginalMicrosecondDeadline(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:snapshot_box",maxTicks=50) @Override
    public void FasterAdsDoesNotShortenBoxBreathingAndTheRealProjectileKeepsItsCapturedBonus(GameTestHelper h)throws Exception {super.FasterAdsDoesNotShortenBoxBreathingAndTheRealProjectileKeepsItsCapturedBonus(h);}
}
