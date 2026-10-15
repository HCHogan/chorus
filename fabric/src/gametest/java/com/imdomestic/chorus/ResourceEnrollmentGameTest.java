package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ResourceEnrollmentGameTest extends com.imdomestic.chorus.test.ResourceEnrollmentGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void firstSelectionAutomaticallyEnrollsAndRepeatedPrepareOrRestoreNeverRefills(GameTestHelper h){super.firstSelectionAutomaticallyEnrollsAndRepeatedPrepareOrRestoreNeverRefills(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void firstActorConfigurationEnrollsBeforeItsResourceAndHealingReactions(GameTestHelper h){super.firstActorConfigurationEnrollsBeforeItsResourceAndHealingReactions(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void firstPhysicalEquipmentSwapEnrollsBeforeAttachedResourceInitialization(GameTestHelper h){super.firstPhysicalEquipmentSwapEnrollsBeforeAttachedResourceInitialization(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void loadedPlayerSelectionIsEnrolledBeforeAutomaticSourceAttachment(GameTestHelper h){super.loadedPlayerSelectionIsEnrolledBeforeAutomaticSourceAttachment(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void playerJoiningAnAlreadyInstalledGroupIsAdmittedBeforeLoadedSelectionRuns(GameTestHelper h){super.playerJoiningAnAlreadyInstalledGroupIsAdmittedBeforeLoadedSelectionRuns(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void unindexedPlayerCannotCreateAResourceOwnerOrCommitConfiguration(GameTestHelper h){super.unindexedPlayerCannotCreateAResourceOwnerOrCommitConfiguration(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void physicalArrivalPreservesAnExistingEmptyRemoteOwnerAndRoutesLaterInitializationThere(GameTestHelper h){super.physicalArrivalPreservesAnExistingEmptyRemoteOwnerAndRoutesLaterInitializationThere(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void unknownFirstAttachmentRetainsAutoEnrollmentAndPhysicalWriteWithoutReplay(GameTestHelper h){super.unknownFirstAttachmentRetainsAutoEnrollmentAndPhysicalWriteWithoutReplay(h);}
}
