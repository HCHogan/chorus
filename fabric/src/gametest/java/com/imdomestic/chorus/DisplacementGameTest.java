package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DisplacementGameTest extends com.imdomestic.chorus.test.DisplacementGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:displacement_ceiling") @Override
    public void fullBoundingBoxClipsAtCeilingAndReanchorsAnExistingHold(GameTestHelper h){super.fullBoundingBoxClipsAtCeilingAndReanchorsAnExistingHold(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:displacement_wall") @Override
    public void wallAndSlabClipTheWholeBodyWithoutStepUpOrChangingVelocity(GameTestHelper h){super.wallAndSlabClipTheWholeBodyWithoutStepUpOrChangingVelocity(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:displacement_rejection") @Override
    public void ineligibleTargetsInvalidDirectionsAndHostBudgetsDoNotMoveTheEntity(GameTestHelper h){super.ineligibleTargetsInvalidDirectionsAndHostBudgetsDoNotMoveTheEntity(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:displacement_player") @Override
    public void playerRelocationUsesNativeTeleportAcknowledgementAndPreservesKnownVelocity(GameTestHelper h) throws Exception{super.playerRelocationUsesNativeTeleportAcknowledgementAndPreservesKnownVelocity(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:displacement_lifetime",maxTicks=120) @Override
    public void periodicLiftUsesActualDistanceStopsAtHeightAndExpiryReleasesGravity(GameTestHelper h){super.periodicLiftUsesActualDistanceStopsAtHeightAndExpiryReleasesGravity(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:displacement_clip_timer",maxTicks=25) @Override
    public void clippedRiseStopsItsTimerAndCleansingReleasesTheCeilingHold(GameTestHelper h){super.clippedRiseStopsItsTimerAndCleansingReleasesTheCeilingHold(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:displacement_unknown") @Override
    public void uncertainWorldCompletionStopsWithoutRepeatingAnAppliedRelocation(GameTestHelper h){super.uncertainWorldCompletionStopsWithoutRepeatingAnAppliedRelocation(h);}
}
