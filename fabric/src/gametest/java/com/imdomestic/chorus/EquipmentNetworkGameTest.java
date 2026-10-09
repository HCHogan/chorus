package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EquipmentNetworkGameTest extends com.imdomestic.chorus.test.EquipmentNetworkGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void wireRoundTripsPreserveItemComponentsAndReplayCannotSwapTwiceOrTargetAnotherPlayer(GameTestHelper h) throws Exception { super.wireRoundTripsPreserveItemComponentsAndReplayCannotSwapTwiceOrTargetAnotherPlayer(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void changedBagAndReplacedRuntimeInvalidateThePublishedViewBeforeMutation(GameTestHelper h) throws Exception { super.changedBagAndReplacedRuntimeInvalidateThePublishedViewBeforeMutation(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void cursorOwnershipAndScreenSessionChangesRejectOldActionsAndClosedViewsStopUpdating(GameTestHelper h) throws Exception { super.cursorOwnershipAndScreenSessionChangesRejectOldActionsAndClosedViewsStopUpdating(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void failureResponseShowsCommittedItemsAndRecoveryUsesTheNewAuthoritativeView(GameTestHelper h) throws Exception { super.failureResponseShowsCommittedItemsAndRecoveryUsesTheNewAuthoritativeView(h); }
}
