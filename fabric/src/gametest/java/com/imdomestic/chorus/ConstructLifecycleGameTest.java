package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ConstructLifecycleGameTest extends com.imdomestic.chorus.test.ConstructLifecycleGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:construct_end_discard") @Override
    public void directRemovalSettlesBeforeCheckpointAndCancelsOnlyItsOwnTimersOnce(GameTestHelper h) { super.directRemovalSettlesBeforeCheckpointAndCancelsOnlyItsOwnTimersOnce(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:construct_end_unload", maxTicks=25) @Override
    public void directSetRemovedAndNaturalExpiryKeepDistinctReasonsAfterOwnerDisappears(GameTestHelper h) { super.directSetRemovedAndNaturalExpiryKeepDistinctReasonsAfterOwnerDisappears(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:construct_end_death", maxTicks=25) @Override
    public void nativeDeathHasOneKillFollowedByOneDestructionFact(GameTestHelper h) { super.nativeDeathHasOneKillFollowedByOneDestructionFact(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:construct_end_world") @Override
    public void worldRemovalWaitsForTheParentFrameAndUnknownResultsNeverReplay(GameTestHelper h) { super.worldRemovalWaitsForTheParentFrameAndUnknownResultsNeverReplay(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:construct_end_close") @Override
    public void runtimeCloseDropsPendingEndFactsAndDoesNotInventRewards(GameTestHelper h) { super.runtimeCloseDropsPendingEndFactsAndDoesNotInventRewards(h); }
}
