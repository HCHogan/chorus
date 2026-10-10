package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ThreadedSpikeGameTest extends com.imdomestic.chorus.test.ThreadedSpikeGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void ninePhysicalTargetsUseTheDecaySeriesAndLeaveTheTenthUntouched(GameTestHelper h) throws Exception { super.ninePhysicalTargetsUseTheDecaySeriesAndLeaveTheTenthUntouched(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualKillsFeedCaughtEnergyAndMailWithoutShorteningAnEarlierGrant(GameTestHelper h) throws Exception { super.actualKillsFeedCaughtEnergyAndMailWithoutShorteningAnEarlierGrant(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:threaded_spike_return", maxTicks = 50) @Override
    public void realTicksAutomaticallyReturnAndCombineTheReferenceGainWithBaseRecovery(GameTestHelper h) throws Exception { super.realTicksAutomaticallyReturnAndCombineTheReferenceGainWithBaseRecovery(h); }
}
