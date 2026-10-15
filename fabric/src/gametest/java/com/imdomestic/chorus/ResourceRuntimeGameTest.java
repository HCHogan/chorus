package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ResourceRuntimeGameTest extends com.imdomestic.chorus.test.ResourceRuntimeGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void installedRuntimeReadsRemoteAccountsBeforePhysicalHealingAndNativeDamage(GameTestHelper h){super.installedRuntimeReadsRemoteAccountsBeforePhysicalHealingAndNativeDamage(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void attachmentInitializesResourcesBeforeAnyDependentNativeProjection(GameTestHelper h){super.attachmentInitializesResourcesBeforeAnyDependentNativeProjection(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void lostWorldReceiptPreservesRemoteGrantAndActualHealingWithoutRetry(GameTestHelper h){super.lostWorldReceiptPreservesRemoteGrantAndActualHealingWithoutRetry(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void groupCheckpointRestoresBothDomainsAndAutomaticEntityLoadProjectionWithoutReplay(GameTestHelper h){super.groupCheckpointRestoresBothDomainsAndAutomaticEntityLoadProjectionWithoutReplay(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void playerSelectionPublishesBeforeReactionsAndPaidInputUsesOneLocalOwner(GameTestHelper h){super.playerSelectionPublishesBeforeReactionsAndPaidInputUsesOneLocalOwner(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void unknownAttachmentKeepsCommittedPlayerSelectionAndStopsReplay(GameTestHelper h){super.unknownAttachmentKeepsCommittedPlayerSelectionAndStopsReplay(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void realEquipmentTransferCommitsContainerAndDomainBeforeAttachedEffects(GameTestHelper h)throws Exception{super.realEquipmentTransferCommitsContainerAndDomainBeforeAttachedEffects(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void explicitGroupRejectsPartialCloseAndDefaultSaveAndFailedAdmissionLeavesExistingHostHealthy(GameTestHelper h){super.explicitGroupRejectsPartialCloseAndDefaultSaveAndFailedAdmissionLeavesExistingHostHealthy(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void failedRestoreCleansEveryMemberAndFailedAttachmentRequiresClosingTheWholeGroup(GameTestHelper h){super.failedRestoreCleansEveryMemberAndFailedAttachmentRequiresClosingTheWholeGroup(h);}
}
