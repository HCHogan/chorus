package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ConstructGameTest extends com.imdomestic.chorus.test.ConstructGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:construct_damage", maxTicks=30) @Override
    public void realHealthDeathAndTargetObservationStopOnlyTheDestroyedConstruct(GameTestHelper h) { super.realHealthDeathAndTargetObservationStopOnlyTheDestroyedConstruct(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:construct_lifetime", maxTicks=25) @Override
    public void ownerRemovalDoesNotEndLifetimeAndNoPulseRunsAtTheExpiryBoundary(GameTestHelper h) { super.ownerRemovalDoesNotEndLifetimeAndNoPulseRunsAtTheExpiryBoundary(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:construct_rejected") @Override
    public void unavailableTerrainFullBodyObstructionAndNativeLimitsRejectWithoutLoadingChunks(GameTestHelper h) { super.unavailableTerrainFullBodyObstructionAndNativeLimitsRejectWithoutLoadingChunks(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:construct_unknown", maxTicks=15) @Override
    public void unknownSpawnCompletionRetainsWorldMutationWithoutRetryOrBehavior(GameTestHelper h) { super.unknownSpawnCompletionRetainsWorldMutationWithoutRetryOrBehavior(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:construct_close") @Override
    public void runtimeCloseImmediatelyDiscardsOnlyItsOwnConstructs(GameTestHelper h) { super.runtimeCloseImmediatelyDiscardsOnlyItsOwnConstructs(h); }
}
