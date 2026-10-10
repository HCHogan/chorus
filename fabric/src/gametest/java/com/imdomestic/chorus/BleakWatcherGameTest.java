package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class BleakWatcherGameTest extends com.imdomestic.chorus.test.BleakWatcherGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void switchingGrenadesAfterAnActualThrowCannotAccessAnotherFullCharge(GameTestHelper h) { super.switchingGrenadesAfterAnActualThrowCannotAccessAnotherFullCharge(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:bleak_shared_energy", maxTicks=30) @Override
    public void selectionAndEmptySlotSplitNativeRecoveryWhileAspectRemainsEquipped(GameTestHelper h) { super.selectionAndEmptySlotSplitNativeRecoveryWhileAspectRemainsEquipped(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:bleak_aspect_energy", maxTicks=25) @Override
    public void equippingAndRemovingAspectSplitsNativeTickRecoveryWithoutResettingThePool(GameTestHelper h) { super.equippingAndRemovingAspectSplitsNativeTickRecoveryWithoutResettingThePool(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void currentAspectScalarPrecedesStatGainAndFixedEnergyStillDrivesNativeHealing(GameTestHelper h) { super.currentAspectScalarPrecedesStatGainAndFixedEnergyStillDrivesNativeHealing(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownGainFollowupRetainsTheScaledEnergyAndActualHealingWithoutReplay(GameTestHelper h) { super.unknownGainFollowupRetainsTheScaledEnergyAndActualHealingWithoutReplay(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:bleak_held_input", maxTicks=55) @Override
    public void serverMeasuredHoldConvertsTheSelectedGrenadeOnlyWithEquippedConversionSource(GameTestHelper h) { super.serverMeasuredHoldConvertsTheSelectedGrenadeOnlyWithEquippedConversionSource(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:bleak_conversion", maxTicks=45) @Override
    public void convertedDuskfieldPaysItsSelectedEnergyAndDeploysARealTurretWithBleakCredit(GameTestHelper h) { super.convertedDuskfieldPaysItsSelectedEnergyAndDeploysARealTurretWithBleakCredit(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:bleak_burst", maxTicks=50) @Override
    public void realFirstBurstRemovesInitialResistanceHitsFiveTimesAndFreezesACombatant(GameTestHelper h) { super.realFirstBurstRemovesInitialResistanceHitsFiveTimesAndFreezesACombatant(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:bleak_guardian", maxTicks=90) @Override
    public void twoPhysicalBurstsApplyTenStacksPerGuardianHitAndReachFreeze(GameTestHelper h) { super.twoPhysicalBurstsApplyTenStacksPerGuardianHitAndReachFreeze(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:bleak_destroyed", maxTicks=45) @Override
    public void destroyingTheTurretStopsFutureShotsButItsFirstProjectileStillAppliesSlow(GameTestHelper h) { super.destroyingTheTurretStopsFutureShotsButItsFirstProjectileStillAppliesSlow(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:bleak_lifetime", maxTicks=640) @Override
    public void alliedTurretsDoNotTargetEachOtherAndKeepIndependentDuranceLifetimes(GameTestHelper h) { super.alliedTurretsDoNotTargetEachOtherAndKeepIndependentDuranceLifetimes(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:bleak_alliance", maxTicks=45) @Override
    public void allianceChangesBeforeImpactPreventFriendlyDamageAndUnknownOwnersAreNotEnemies(GameTestHelper h) { super.allianceChangesBeforeImpactPreventFriendlyDamageAndUnknownOwnersAreNotEnemies(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:bleak_fault", maxTicks=40) @Override
    public void unknownPhysicalHitKeepsDamageAndSpentEnergyWithoutReplayingOrApplyingSlow(GameTestHelper h) { super.unknownPhysicalHitKeepsDamageAndSpentEnergyWithoutReplayingOrApplyingSlow(h); }
}
