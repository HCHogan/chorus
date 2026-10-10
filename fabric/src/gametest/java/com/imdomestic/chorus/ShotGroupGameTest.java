package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ShotGroupGameTest extends com.imdomestic.chorus.test.ShotGroupGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:shot_group_hits", maxTicks = 20) @Override
    public void physicalPelletsAggregateActualReceiptsOnceAfterStow(GameTestHelper h) throws Exception { super.physicalPelletsAggregateActualReceiptsOnceAfterStow(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:shot_group_removed", maxTicks = 60) @Override
    public void externallyRemovedPelletProducesIncompleteSummaryAtDeadline(GameTestHelper h) throws Exception { super.externallyRemovedPelletProducesIncompleteSummaryAtDeadline(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:shot_group_lifetime", maxTicks = 20) @Override
    public void explicitShotLifetimeStopsPhysicalFlightAndCleansUnknownSlots(GameTestHelper h) throws Exception { super.explicitShotLifetimeStopsPhysicalFlightAndCleansUnknownSlots(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:shot_group_unknown", maxTicks = 20) @Override
    public void unknownWorldDamageKeepsContactOpenAndCannotReplayOrResolve(GameTestHelper h) throws Exception { super.unknownWorldDamageKeepsContactOpenAndCannotReplayOrResolve(h); }
}
