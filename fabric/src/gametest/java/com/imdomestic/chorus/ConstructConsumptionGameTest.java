package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ConstructConsumptionGameTest extends com.imdomestic.chorus.test.ConstructConsumptionGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:construct_consume_once", maxTicks = 100) @Override
    public void nativeConsumeRemovesOneExactConstructAndRewardsOnceWithoutDamageOrDeath(GameTestHelper h) { super.nativeConsumeRemovesOneExactConstructAndRewardsOnceWithoutDamageOrDeath(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:construct_consume_self", maxTicks = 100) @Override
    public void consumingFromItsOwnBuffFinishesDependentActionsBeforeQueuedLifecycleCleanup(GameTestHelper h) { super.consumingFromItsOwnBuffFinishesDependentActionsBeforeQueuedLifecycleCleanup(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:construct_consume_identity", maxTicks = 100) @Override
    public void staleLifeWrongDimensionAndOrdinaryEntitiesCannotBeConsumed(GameTestHelper h) { super.staleLifeWrongDimensionAndOrdinaryEntitiesCannotBeConsumed(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:construct_consume_inactive", maxTicks = 100) @Override
    public void deadAndUnmanagedConstructsCannotBeClaimedForRewards(GameTestHelper h) { super.deadAndUnmanagedConstructsCannotBeClaimedForRewards(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:construct_consume_expired", maxTicks = 20) @Override
    public void anExpiredConstructCannotProduceAConsumptionReward(GameTestHelper h) { super.anExpiredConstructCannotProduceAConsumptionReward(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:construct_consume_unknown", maxTicks = 20) @Override
    public void unknownNativeConsumptionKeepsRemovalAndPriorCommitWithoutRewardOrRetry(GameTestHelper h) { super.unknownNativeConsumptionKeepsRemovalAndPriorCommitWithoutRewardOrRetry(h); }
}
