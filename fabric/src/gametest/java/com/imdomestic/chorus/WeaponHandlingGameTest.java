package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponHandlingGameTest extends com.imdomestic.chorus.test.WeaponHandlingGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_handling_timing", maxTicks=35) @Override
    public void actualSelectionCommitsAfterBothPhasesAndFireReloadRespectTheWait(GameTestHelper h) throws Exception { super.actualSelectionCommitsAfterBothPhasesAndFireReloadRespectTheWait(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_handling_resample", maxTicks=30) @Override
    public void physicalStowKeepsAcceptedTimeAndReadySeesStowTriggeredModifiers(GameTestHelper h) throws Exception { super.physicalStowKeepsAcceptedTimeAndReadySeesStowTriggeredModifiers(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_handling_remove", maxTicks=30) @Override
    public void removingAndReequippingTheTargetCannotReviveItsOldSwitch(GameTestHelper h) throws Exception { super.removingAndReequippingTheTargetCannotReviveItsOldSwitch(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_handling_network", maxTicks=25) @Override
    public void authenticatedSelectionConsumesRevisionAndOldViewCannotCancelNewIntent(GameTestHelper h) throws Exception { super.authenticatedSelectionConsumesRevisionAndOldViewCannotCancelNewIntent(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_handling_death", maxTicks=25) @Override
    public void deadOwnerLosesPendingSelectionBeforeItsOldCompletion(GameTestHelper h) throws Exception { super.deadOwnerLosesPendingSelectionBeforeItsOldCompletion(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_handling_spectator", maxTicks=25) @Override
    public void hostRejectsAReadyCompletionAfterTheOwnerBecomesSpectator(GameTestHelper h) throws Exception { super.hostRejectsAReadyCompletionAfterTheOwnerBecomesSpectator(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_handling_unknown", maxTicks=25) @Override
    public void unknownDrawnReactionRetainsPhysicalAndDomainSelectionWithoutReplay(GameTestHelper h) throws Exception { super.unknownDrawnReactionRetainsPhysicalAndDomainSelectionWithoutReplay(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_handling_restore_ready", maxTicks=25) @Override
    public void readyCheckpointResumesAgainstTheSamePhysicalContainerAtItsOriginalDeadline(GameTestHelper h) throws Exception { super.readyCheckpointResumesAgainstTheSamePhysicalContainerAtItsOriginalDeadline(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_handling_restore_stow", maxTicks=30) @Override
    public void stowCheckpointResumesThenAcceptsTheNewWeaponsReadyPhase(GameTestHelper h) throws Exception { super.stowCheckpointResumesThenAcceptsTheNewWeaponsReadyPhase(h); }
}
