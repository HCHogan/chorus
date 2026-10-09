package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class TargetIterationGameTest extends com.imdomestic.chorus.test.TargetIterationGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void sphereUsesFeetDistanceIncludesBoundaryAndFiltersDeadAndRemovedTargets(GameTestHelper h) { super.sphereUsesFeetDistanceIncludesBoundaryAndFiltersDeadAndRemovedTargets(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void relationUsesExplicitReferenceTeamsAndMissingReferenceIsNotAnEmptySuccess(GameTestHelper h) { super.relationUsesExplicitReferenceTeamsAndMissingReferenceIsNotAnEmptySuccess(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void areaDamageAndLeechRunEverySelectedTargetWithIndependentWorldReceipts(GameTestHelper h) throws Exception { super.areaDamageAndLeechRunEverySelectedTargetWithIndependentWorldReceipts(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void selectionStaysFixedWhenTargetsMoveDisappearAndArriveDuringWorldWaits(GameTestHelper h) throws Exception { super.selectionStaysFixedWhenTargetsMoveDisappearAndArriveDuringWorldWaits(h); }
}
