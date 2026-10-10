package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class DamageTallyGameTest extends com.imdomestic.chorus.test.DamageTallyGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:tally_return", maxTicks = 60) @Override
    public void realTicksChainThreeTargetsReturnAndPayForEarlierKillsAfterSelectionClears(GameTestHelper h) throws Exception { super.realTicksChainThreeTargetsReturnAndPayForEarlierKillsAfterSelectionClears(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void catchUsesWholeCastReceiptsAndConsumesTallyBeforeWorldPayoff(GameTestHelper h) throws Exception { super.catchUsesWholeCastReceiptsAndConsumesTallyBeforeWorldPayoff(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void zeroHitExpiryStillReturnsWithoutInventingHitOrKillRewards(GameTestHelper h) throws Exception { super.zeroHitExpiryStillReturnsWithoutInventingHitOrKillRewards(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:tally_expiry", maxTicks = 30) @Override
    public void abandonedFlightWithoutAnyCallbackStillReleasesTallyAtDeadline(GameTestHelper h) throws Exception { super.abandonedFlightWithoutAnyCallbackStillReleasesTallyAtDeadline(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownDamageNeverBecomesConfirmedTallyAndUnknownPayoffCannotReopenIt(GameTestHelper h) throws Exception { super.unknownDamageNeverBecomesConfirmedTallyAndUnknownPayoffCannotReopenIt(h); }
}
