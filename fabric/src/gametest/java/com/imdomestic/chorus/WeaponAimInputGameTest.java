package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponAimInputGameTest extends com.imdomestic.chorus.test.WeaponAimInputGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void relocatedAimPayloadPreservesSignedLocalPhaseTimesThroughNativeCodec(GameTestHelper h){super.relocatedAimPayloadPreservesSignedLocalPhaseTimesThroughNativeCodec(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:aim_input_packets",maxTicks=30) @Override
    public void PairedPacketsAreOrderedAndCannotReleaseAnotherGestureOrDimension(GameTestHelper h)throws Exception {super.PairedPacketsAreOrderedAndCannotReleaseAnotherGestureOrDimension(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:aim_input_switch",maxTicks=50) @Override
    public void HeldIntentWaitsForReloadAndSwitchAndReleaseDuringReadyPreventsReentry(GameTestHelper h)throws Exception {super.HeldIntentWaitsForReloadAndSwitchAndReleaseDuringReadyPreventsReentry(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:aim_input_lease",maxTicks=60) @Override
    public void MissingHeartbeatExpiresAtDeadlineAndLateHoldCannotResurrectIt(GameTestHelper h)throws Exception {super.MissingHeartbeatExpiresAtDeadlineAndLateHoldCannotResurrectIt(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:aim_input_cancel",maxTicks=30) @Override
    public void CancelSpectatorAndDisconnectRetireTheGestureWithoutAnExitWait(GameTestHelper h)throws Exception {super.CancelSpectatorAndDisconnectRetireTheGestureWithoutAnExitWait(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:aim_input_gate",maxTicks=35) @Override
    public void ContinueRestrictionInterruptsAimedStateAndRecoveryUsesANewEntry(GameTestHelper h)throws Exception {super.ContinueRestrictionInterruptsAimedStateAndRecoveryUsesANewEntry(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:aim_input_runtime",maxTicks=30) @Override
    public void RestoredDomainAimDoesNotInheritAnOldConnectionGesture(GameTestHelper h)throws Exception {super.RestoredDomainAimDoesNotInheritAnOldConnectionGesture(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:aim_input_unknown",maxTicks=30) @Override
    public void UnknownAimReactionConsumesSequenceAndRetiresAutomaticRetries(GameTestHelper h)throws Exception {super.UnknownAimReactionConsumesSequenceAndRetiresAutomaticRetries(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:aim_input_box",maxTicks=90) @Override
    public void HeartbeatsKeepBoxBreathingAndReloadRequiresANewFullWait(GameTestHelper h)throws Exception {super.HeartbeatsKeepBoxBreathingAndReloadRequiresANewFullWait(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:aim_input_quiesce",maxTicks=30) @Override
    public void QuiescenceClearsLiveAimBeforeCapturingACleanStopCheckpoint(GameTestHelper h)throws Exception {super.QuiescenceClearsLiveAimBeforeCapturingACleanStopCheckpoint(h);}

}
