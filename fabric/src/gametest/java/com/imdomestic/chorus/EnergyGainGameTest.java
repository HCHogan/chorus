package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EnergyGainGameTest extends com.imdomestic.chorus.test.EnergyGainGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void baseReferenceAndFixedGainsCreditTheActualAbilityAccountExactlyOnce(GameTestHelper h) throws Exception { super.baseReferenceAndFixedGainsCreditTheActualAbilityAccountExactlyOnce(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:energy_stat_change", maxTicks = 45) @Override
    public void realTickStatChangesSettleTheOldPassiveRateBeforeUsingTheNewCurve(GameTestHelper h) throws Exception { super.realTickStatChangesSettleTheOldPassiveRateBeforeUsingTheNewCurve(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownLaterWorldResultKeepsTheAlreadyCreditedGain(GameTestHelper h) throws Exception { super.unknownLaterWorldResultKeepsTheAlreadyCreditedGain(h); }
}
