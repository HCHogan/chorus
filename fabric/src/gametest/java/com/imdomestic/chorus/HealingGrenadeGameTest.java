package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class HealingGrenadeGameTest extends com.imdomestic.chorus.test.HealingGrenadeGameTest {
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_homing", maxTicks = 100) @Override
    public void paidPhysicalFlightHomesToAllyAndCreatesConsumedRestorationOrb(GameTestHelper h) { super.paidPhysicalFlightHomesToAllyAndCreatesConsumedRestorationOrb(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_touch", maxTicks = 100) @Override
    public void capturedTouchStrengthSurvivesUnbindAndComposesWithCollectorSolace(GameTestHelper h) { super.capturedTouchStrengthSurvivesUnbindAndComposesWithCollectorSolace(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_floor", maxTicks = 60) @Override
    public void floorImpactPlacesEntireOrbAboveTheContact(GameTestHelper h) { super.floorImpactPlacesEntireOrbAboveTheContact(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_wall", maxTicks = 60) @Override
    public void wallImpactPlacesEntireOrbOutsideTheWall(GameTestHelper h) { super.wallImpactPlacesEntireOrbOutsideTheWall(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_ceiling", maxTicks = 60) @Override
    public void ceilingImpactPlacesEntireOrbBelowTheCeiling(GameTestHelper h) { super.ceilingImpactPlacesEntireOrbBelowTheCeiling(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_late", maxTicks = 100) @Override
    public void unclaimedOrbWaitsForFirstAlliedBatchAndNeverRewardsSubsequentArrivals(GameTestHelper h) { super.unclaimedOrbWaitsForFirstAlliedBatchAndNeverRewardsSubsequentArrivals(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_expiry", maxTicks = 190) @Override
    public void nativeOrbExpiresAfterSevenPointFiveSecondsWithoutAnEligibleCollector(GameTestHelper h) { super.nativeOrbExpiresAfterSevenPointFiveSecondsWithoutAnEligibleCollector(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_destroyed", maxTicks = 60) @Override
    public void nativeDestructionBeforeFirstScanCancelsRestoration(GameTestHelper h) { super.nativeDestructionBeforeFirstScanCancelsRestoration(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_denied", maxTicks = 60) @Override
    public void deniedStatusDoesNotRefundPaidThrowOrRecreateConsumedOrb(GameTestHelper h) { super.deniedStatusDoesNotRefundPaidThrowOrRecreateConsumedOrb(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_unknown", maxTicks = 60) @Override
    public void unknownNativeClaimKeepsPhysicalConsumptionAndNeverReplaysOrGrantsRestoration(GameTestHelper h) { super.unknownNativeClaimKeepsPhysicalConsumptionAndNeverReplaysOrGrantsRestoration(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_consumed", maxTicks = 100) @Override
    public void actualHeldHeatRisesInputConsumesRealHealingGrenadeWithoutThrowing(GameTestHelper h) { super.actualHeldHeatRisesInputConsumesRealHealingGrenadeWithoutThrowing(h); }
}
