package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class HorizontalSpeedGameTest extends com.imdomestic.chorus.test.HorizontalSpeedGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:horizontal_speed_budget") @Override
    public void velocityAndEveryCoordinateWriteShareOneRadialBudget(GameTestHelper h){super.velocityAndEveryCoordinateWriteShareOneRadialBudget(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:horizontal_speed_collision") @Override
    public void collisionsPreserveUnusedTravelAndAxisLocksComposeWithImpulseReceipts(GameTestHelper h){super.collisionsPreserveUnusedTravelAndAxisLocksComposeWithImpulseReceipts(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:horizontal_speed_tick",maxTicks=25) @Override
    public void newTickResetsOnlyOneTickOfTravelAndIdleTimeCannotAccumulate(GameTestHelper h){super.newTickResetsOnlyOneTickOfTravelAndIdleTimeCannotAccumulate(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:horizontal_speed_packets") @Override
    public void forgedPacketsUseActualBudgetAndAuthorizedTeleportsPreserveRestrictions(GameTestHelper h) throws Exception{super.forgedPacketsUseActualBudgetAndAuthorizedTeleportsPreserveRestrictions(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:horizontal_speed_expiry",maxTicks=18) @Override
    public void recipientExpiryRestoresSourceCeilingAndCloseRemovesProjection(GameTestHelper h){super.recipientExpiryRestoresSourceCeilingAndCloseRemovesProjection(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:horizontal_speed_failure") @Override
    public void failureIsVisibleAndOldOwnersCannotClearNewerProjection(GameTestHelper h){super.failureIsVisibleAndOldOwnersCannotClearNewerProjection(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:horizontal_speed_mount") @Override
    public void applicationDismountsAndReleaseAllowsRidingAgain(GameTestHelper h){super.applicationDismountsAndReleaseAllowsRidingAgain(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:horizontal_speed_wire") @Override
    public void wireDistinguishesClearZeroAndPositiveCeilingsAndRejectsNonfiniteValues(GameTestHelper h){super.wireDistinguishesClearZeroAndPositiveCeilingsAndRejectsNonfiniteValues(h);}
}
