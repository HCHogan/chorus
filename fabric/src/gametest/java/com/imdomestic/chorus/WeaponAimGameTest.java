package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponAimGameTest extends com.imdomestic.chorus.test.WeaponAimGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_aim_input",maxTicks=35) @Override
    public void ordinaryInputSeparatesAimReadinessAndActualShotKeepsItAfterRelease(GameTestHelper h)throws Exception {super.ordinaryInputSeparatesAimReadinessAndActualShotKeepsItAfterRelease(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_aim_cancel",maxTicks=45) @Override
    public void AcceptedReloadAndSwitchCancelAimButFullReloadDoesNot(GameTestHelper h)throws Exception {super.AcceptedReloadAndSwitchCancelAimButFullReloadDoesNot(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_aim_reverse",maxTicks=35) @Override
    public void TimingChangesAffectNewEdgesAndCancelledDeadlinesCannotFinishTheirReplacement(GameTestHelper h)throws Exception {super.TimingChangesAffectNewEdgesAndCancelledDeadlinesCannotFinishTheirReplacement(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_aim_remove",maxTicks=30) @Override
    public void RemovingTheHeldWeaponCancelsAimEvenIfTheSameItemIsPutBack(GameTestHelper h)throws Exception {super.RemovingTheHeldWeaponCancelsAimEvenIfTheSameItemIsPutBack(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_aim_spectator",maxTicks=30) @Override
    public void BecomingSpectatorClearsCompletedAimAndCannotStartAnother(GameTestHelper h)throws Exception {super.BecomingSpectatorClearsCompletedAimAndCannotStartAnother(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_aim_restore_enter",maxTicks=30) @Override
    public void EnteringCheckpointCompletesAgainstTheOwnedContainerOnItsFirstResumedTick(GameTestHelper h)throws Exception {super.EnteringCheckpointCompletesAgainstTheOwnedContainerOnItsFirstResumedTick(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_aim_restore_exit",maxTicks=30) @Override
    public void ExitingCheckpointEndsOnItsOriginalDeadlineWithoutReplayingRelease(GameTestHelper h)throws Exception {super.ExitingCheckpointEndsOnItsOriginalDeadlineWithoutReplayingRelease(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_aim_unknown",maxTicks=30) @Override
    public void UnknownReadyReactionKeepsCompletedAimWithoutReplayingItsFact(GameTestHelper h)throws Exception {super.UnknownReadyReactionKeepsCompletedAimWithoutReplayingItsFact(h);}
}
