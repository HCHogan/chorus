package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PrecisionAmmoGameTest extends com.imdomestic.chorus.test.PrecisionAmmoGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:precision_ammo_cycles", maxTicks = 80) @Override
    public void repeatedRealPrecisionShotsComposeBothPerksAndReloadAloneConsumesReserves(GameTestHelper h) throws Exception { super.repeatedRealPrecisionShotsComposeBothPerksAndReloadAloneConsumesReserves(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:precision_ammo_gap", maxTicks = 140) @Override
    public void realBodyContactDoesNotRefreshGapAndEnhancedCounterSurvivesStowForLonger(GameTestHelper h) throws Exception { super.realBodyContactDoesNotRefreshGapAndEnhancedCounterSurvivesStowForLonger(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void immuneBodyAndUncreditedPhysicalContactsNeverCount(GameTestHelper h) throws Exception { super.immuneBodyAndUncreditedPhysicalContactsNeverCount(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void removingActualWeaponInFlightPreventsItsPerkFromCountingLaterImpact(GameTestHelper h) throws Exception { super.removingActualWeaponInFlightPreventsItsPerkFromCountingLaterImpact(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:precision_ammo_unknown", maxTicks = 30) @Override
    public void unknownThirdPrecisionReceiptRetainsDamageAndDebitWithoutGeneratingOrReplaying(GameTestHelper h) throws Exception { super.unknownThirdPrecisionReceiptRetainsDamageAndDebitWithoutGeneratingOrReplaying(h); }
}
