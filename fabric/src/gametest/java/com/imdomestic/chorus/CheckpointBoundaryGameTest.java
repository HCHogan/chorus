package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class CheckpointBoundaryGameTest extends com.imdomestic.chorus.test.CheckpointBoundaryGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:checkpoint_boundary") @Override
    public void pendingConstructRemovalInvalidatesPinnedArchiveBeforeAnyRulesExecute(GameTestHelper h) { super.pendingConstructRemovalInvalidatesPinnedArchiveBeforeAnyRulesExecute(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:checkpoint_boundary") @Override
    public void pinnedNbtLoadPreservesProjectedHealthAndDetectsPhysicalEquipmentChanges(GameTestHelper h) { super.pinnedNbtLoadPreservesProjectedHealthAndDetectsPhysicalEquipmentChanges(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:checkpoint_boundary") @Override
    public void capturesAllDefaultRuntimesAndSavesRealNativeFilesWithoutReplayingRules(GameTestHelper h) throws Exception { super.capturesAllDefaultRuntimesAndSavesRealNativeFilesWithoutReplayingRules(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:checkpoint_boundary") @Override
    public void caughtRuleCallsCannotChangeStateOrMakeTheSameBoundaryValidAgain(GameTestHelper h) { super.caughtRuleCallsCannotChangeStateOrMakeTheSameBoundaryValidAgain(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:checkpoint_boundary") @Override
    public void preventsNewRuntimeAndNestedBoundaryAndKeepsOffThreadCloseFromReleasingIt(GameTestHelper h) throws Exception { super.preventsNewRuntimeAndNestedBoundaryAndKeepsOffThreadCloseFromReleasingIt(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:checkpoint_boundary") @Override
    public void aCaughtRuleOperationInsideNativeSerializationPreventsCheckpointConfirmation(GameTestHelper h) throws Exception { super.aCaughtRuleOperationInsideNativeSerializationPreventsCheckpointConfirmation(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:checkpoint_boundary") @Override
    public void changedClockRemainsInvalidAfterItIsPutBack(GameTestHelper h) { super.changedClockRemainsInvalidAfterItIsPutBack(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:checkpoint_boundary") @Override
    public void refusesCustomHostsBeforeOpeningAndLeavesExistingRuntimeUsable(GameTestHelper h) { super.refusesCustomHostsBeforeOpeningAndLeavesExistingRuntimeUsable(h); }
}
