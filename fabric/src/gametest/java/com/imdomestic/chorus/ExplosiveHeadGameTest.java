package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ExplosiveHeadGameTest extends com.imdomestic.chorus.test.ExplosiveHeadGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:head_near",maxTicks=25) @Override
    public void nearPhysicalContactHasFourTickFuseAndSeparateNativeComponents(GameTestHelper h)throws Exception {super.nearPhysicalContactHasFourTickFuseAndSeparateNativeComponents(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:head_far",maxTicks=30) @Override
    public void distantContactUsesSavedMuzzleAndSourceAfterUnequipThenFollowsTarget(GameTestHelper h)throws Exception {super.distantContactUsesSavedMuzzleAndSourceAfterUnequipThenFollowsTarget(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:head_removed",maxTicks=25) @Override
    public void nativeRemovalRetainsHistoricalExplosionWithoutFollowingReplacement(GameTestHelper h)throws Exception {super.nativeRemovalRetainsHistoricalExplosionWithoutFollowingReplacement(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:head_zero",maxTicks=25) @Override
    public void zeroFuseIsImmediateAndNegativeCalibrationCannotIssueNativeDamage(GameTestHelper h)throws Exception {super.zeroFuseIsImmediateAndNegativeCalibrationCannotIssueNativeDamage(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:head_long",maxTicks=70) @Override
    public void longFuseRetainsAttachmentBeyondOneSecondAndClosesAfterDetonation(GameTestHelper h)throws Exception {super.longFuseRetainsAttachmentBeyondOneSecondAndClosesAfterDetonation(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:head_unknown",maxTicks=25) @Override
    public void unknownDelayedNativeReceiptDoesNotReplayTheAppliedExplosion(GameTestHelper h)throws Exception {super.unknownDelayedNativeReceiptDoesNotReplayTheAppliedExplosion(h);}
}
