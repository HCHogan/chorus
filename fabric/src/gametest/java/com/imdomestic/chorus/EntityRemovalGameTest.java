package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EntityRemovalGameTest extends com.imdomestic.chorus.test.EntityRemovalGameTest {
    @GameTest(structure="chorus_gametest:empty",maxTicks=20) @Override
    public void discardCapturesTheOldLifeAndAllAnchorsOnceBeforeCheckpoint(GameTestHelper h)throws Exception {super.discardCapturesTheOldLifeAndAllAnchorsOnceBeforeCheckpoint(h);}
    @GameTest(structure="chorus_gametest:empty",maxTicks=20) @Override
    public void directSetRemovedKeepsChunkAndPlayerUnloadReasonsWithoutDeathFacts(GameTestHelper h)throws Exception {super.directSetRemovedKeepsChunkAndPlayerUnloadReasonsWithoutDeathFacts(h);}
    @GameTest(structure="chorus_gametest:empty",maxTicks=20) @Override
    public void actualNativeDeathAndLaterRemovalHaveDistinctFacts(GameTestHelper h)throws Exception {super.actualNativeDeathAndLaterRemovalHaveDistinctFacts(h);}
    @GameTest(structure="chorus_gametest:empty",maxTicks=20) @Override
    public void committedRemovalWaitsForParentActionsAndUnknownOutcomeRetainsItsEvidence(GameTestHelper h)throws Exception {super.committedRemovalWaitsForParentActionsAndUnknownOutcomeRetainsItsEvidence(h);}
    @GameTest(structure="chorus_gametest:empty",maxTicks=25,environment="chorus_gametest:entity_removal_delayed") @Override
    public void detachedWorkUsesHistoricalPositionAfterSourceAndTargetDisappear(GameTestHelper h)throws Exception {super.detachedWorkUsesHistoricalPositionAfterSourceAndTargetDisappear(h);}
    @GameTest(structure="chorus_gametest:empty",maxTicks=20) @Override
    public void runtimeCloseDoesNotExecutePendingRemovalRewards(GameTestHelper h)throws Exception {super.runtimeCloseDoesNotExecutePendingRemovalRewards(h);}
    @GameTest(structure="chorus_gametest:empty",maxTicks=20) @Override
    public void actualDimensionTransferRecordsTheOldWorldAndLife(GameTestHelper h)throws Exception {super.actualDimensionTransferRecordsTheOldWorldAndLife(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void immediateReenableInsideTheNativeCallbackCannotEraseCommittedRemoval(GameTestHelper h)throws Exception {super.immediateReenableInsideTheNativeCallbackCannotEraseCommittedRemoval(h);}
}
