package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ResourceProjectionGameTest extends com.imdomestic.chorus.test.ResourceProjectionGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_projections") @Override
    public void nativeAttributesUseFrozenRemoteBalancesAndPreserveExternalModifiers(GameTestHelper h){super.nativeAttributesUseFrozenRemoteBalancesAndPreserveExternalModifiers(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_projections") @Override
    public void remoteGatesFilterRealPlayerInputAndConstrainPhysicalAxes(GameTestHelper h){super.remoteGatesFilterRealPlayerInputAndConstrainPhysicalAxes(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_projections") @Override
    public void remoteSpeedChangesKeepOnePhysicalTravelBudgetAndLeaveVerticalMovementFree(GameTestHelper h){super.remoteSpeedChangesKeepOnePhysicalTravelBudgetAndLeaveVerticalMovementFree(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_projections") @Override
    public void missingAccountsPreserveAtomicAttributesAndFailClosedForEachMovementRecipient(GameTestHelper h){super.missingAccountsPreserveAtomicAttributesAndFailClosedForEachMovementRecipient(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_projections") @Override
    public void missingOrWrongViewsAreRejectedBeforeCleanupIncludingEmptyProjections(GameTestHelper h){super.missingOrWrongViewsAreRejectedBeforeCleanupIncludingEmptyProjections(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_projections") @Override
    public void explicitLoadHookProjectsAnUnindexedReplacementUsingSavedLiveness(GameTestHelper h){super.explicitLoadHookProjectsAnUnindexedReplacementUsingSavedLiveness(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_projections") @Override
    public void activeWorldCallbackProjectsCommittedRemoteEnergyBeforeHealingAndRetainsUnknownWrites(GameTestHelper h){super.activeWorldCallbackProjectsCommittedRemoteEnergyBeforeHealingAndRetainsUnknownWrites(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_projections") @Override
    public void resourceHandoffAndJointRestoreRebuildProjectionsWithoutCopyingForeignProviders(GameTestHelper h){super.resourceHandoffAndJointRestoreRebuildProjectionsWithoutCopyingForeignProviders(h);}
}
