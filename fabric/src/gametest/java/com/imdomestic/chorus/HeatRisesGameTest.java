package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class HeatRisesGameTest extends com.imdomestic.chorus.test.HeatRisesGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_ordinary", maxTicks = 100) @Override
    public void actualShortAndHeldGrenadeInputsPayOnceAndHealSelfAndNineMeterAlly(GameTestHelper h) { super.actualShortAndHeldGrenadeInputsPayOnceAndHealSelfAndNineMeterAlly(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_healing", maxTicks = 100) @Override
    public void consumedHealingGrenadeUsesCureThreeWithoutInventingRestoration(GameTestHelper h) { super.consumedHealingGrenadeUsesCureThreeWithoutInventingRestoration(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_touch", maxTicks = 100) @Override
    public void consumedTouchHealingGrenadeGrantsSharedRestorationWithEachRecipientsSolace(GameTestHelper h) { super.consumedTouchHealingGrenadeGrantsSharedRestorationWithEachRecipientsSolace(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_wisp", maxTicks = 100) @Override
    public void consumedWispPaysTheTransformedAccountAndGrantsCureThreeAndRestorationTwo(GameTestHelper h) { super.consumedWispPaysTheTransformedAccountAndGrantsCureThreeAndRestorationTwo(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_passive", maxTicks = 100) @Override
    public void actualAirborneKillsGrantRankedMeleeEnergyWithoutActiveHeatAndDuplicateSourcesDoNotMultiplyIt(GameTestHelper h) { super.actualAirborneKillsGrantRankedMeleeEnergyWithoutActiveHeatAndDuplicateSourcesDoNotMultiplyIt(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_historical", maxTicks = 100) @Override
    public void nativeDeathObservationKeepsAirborneAndRankFactsAfterLiveEntitiesChange(GameTestHelper h) { super.nativeDeathObservationKeepsAirborneAndRankFactsAfterLiveEntitiesChange(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_reset", maxTicks = 100) @Override
    public void actualKillsCapRemainingTimeAndASecondPaidConsumptionOverwritesLongerDuration(GameTestHelper h) { super.actualKillsCapRemainingTimeAndASecondPaidConsumptionOverwritesLongerDuration(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_composition", maxTicks = 100) @Override
    public void actualConsumedHeatEnablesSecondDashChargeAndHeatedPhoenixPhysicalLanding(GameTestHelper h) { super.actualConsumedHeatEnablesSecondDashChargeAndHeatedPhoenixPhysicalLanding(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_death", maxTicks = 100) @Override
    public void confirmedNativeDeathCancelsHeatRises(GameTestHelper h) { super.confirmedNativeDeathCancelsHeatRises(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_removal", maxTicks = 100) @Override
    public void confirmedNativeRemovalCancelsHeatRises(GameTestHelper h) { super.confirmedNativeRemovalCancelsHeatRises(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heat_rises_unknown", maxTicks = 100) @Override
    public void unknownActualCureRetainsPaymentAndHeatWithoutReplayingHealing(GameTestHelper h) { super.unknownActualCureRetainsPaymentAndHeatWithoutReplayingHealing(h); }
}
