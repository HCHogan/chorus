package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class IcarusDashGameTest extends com.imdomestic.chorus.test.IcarusDashGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void groundedCastIsRejectedAndVerticalLookStillMovesHorizontallyForEightMeters(GameTestHelper h) throws Exception { super.groundedCastIsRejectedAndVerticalLookStillMovesHorizontallyForEightMeters(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:icarus_wall") @Override
    public void daybreakDistanceUsesTenMetersAndWallCollisionKeepsThePaidCharge(GameTestHelper h) { super.daybreakDistanceUsesTenMetersAndWallCollisionKeepsThePaidCharge(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:icarus_transition", maxTicks=105) @Override
    public void realHeatRisesTransitionKeepsPartialProgressAndRestoresTwoPaidDashes(GameTestHelper h) { super.realHeatRisesTransitionKeepsPartialProgressAndRestoresTwoPaidDashes(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:icarus_expiry", maxTicks=115) @Override
    public void realBuffExpiryClipsCapacityBeforeTheSameBoundaryCompletesRecharge(GameTestHelper h) { super.realBuffExpiryClipsCapacityBeforeTheSameBoundaryCompletesRecharge(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void realMeleeSelectionControlsSongOfFlameEligibilityWithoutResettingProgress(GameTestHelper h) { super.realMeleeSelectionControlsSongOfFlameEligibilityWithoutResettingProgress(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownNativeDashResultRetainsActualMovementAndPaymentWithoutReplay(GameTestHelper h) { super.unknownNativeDashResultRetainsActualMovementAndPaymentWithoutReplay(h); }
}
