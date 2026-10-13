package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FireboltGameTest extends com.imdomestic.chorus.test.FireboltGameTest {
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_entity", maxTicks = 100) @Override
    public void primaryEntityCollisionOnlyScansAndTheSecondaryFlightAppliesDamageOnce(GameTestHelper h) { super.primaryEntityCollisionOnlyScansAndTheSecondaryFlightAppliesDamageOnce(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_scan", maxTicks = 100) @Override
    public void paidImpactScansFourVisibleEnemiesBeforeDelayedPhysicalBolts(GameTestHelper h) { super.paidImpactScansFourVisibleEnemiesBeforeDelayedPhysicalBolts(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_touch", maxTicks = 100) @Override
    public void castTouchAndAshesPersistAfterRemovalAndReachTheFifthTargetAtTwelveMeters(GameTestHelper h) { super.castTouchAndAshesPersistAfterRemovalAndReachTheFifthTargetAtTwelveMeters(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_move", maxTicks = 100) @Override
    public void selectedMovingLifeRemainsTheTargetAndNewCloserEnemiesCannotReplaceIt(GameTestHelper h) { super.selectedMovingLifeRemainsTheTargetAndNewCloserEnemiesCannotReplaceIt(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_delay_life", maxTicks = 100) @Override
    public void replacedDeadAndRemovedTargetsDoNotLaunchOrFillVacantSlots(GameTestHelper h) { super.replacedDeadAndRemovedTargetsDoNotLaunchOrFillVacantSlots(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_flight_life", maxTicks = 100) @Override
    public void replacementAfterLaunchCannotReceiveTheOldSeekersDamage(GameTestHelper h) { super.replacementAfterLaunchCannotReceiveTheOldSeekersDamage(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_cover", maxTicks = 100) @Override
    public void coverRaisedAfterTheScanStopsThePhysicalSecondaryBolt(GameTestHelper h) { super.coverRaisedAfterTheScanStopsThePhysicalSecondaryBolt(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_denied", maxTicks = 100) @Override
    public void deniedScorchDoesNotUndoConfirmedBoltDamageOrGrenadePayment(GameTestHelper h) { super.deniedScorchDoesNotUndoConfirmedBoltDamageOrGrenadePayment(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_unknown", maxTicks = 100) @Override
    public void unknownActualBoltDamageStopsBeforeScorchAndNeverReplays(GameTestHelper h) { super.unknownActualBoltDamageStopsBeforeScorchAndNeverReplays(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_heat", maxTicks = 100) @Override
    public void heldHeatRisesConsumesThisRealGrenadeAndNeverExecutesItsThrow(GameTestHelper h) { super.heldHeatRisesConsumesThisRealGrenadeAndNeverExecutesItsThrow(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:firebolt_ignition", maxTicks = 150) @Override
    public void repeatedPaidThrowsReachTheSharedIgnitionWithoutSuppressingLegitimateEffects(GameTestHelper h) { super.repeatedPaidThrowsReachTheSharedIgnitionWithoutSuppressingLegitimateEffects(h); }
}
