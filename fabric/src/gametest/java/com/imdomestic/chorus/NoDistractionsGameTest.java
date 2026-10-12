package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NoDistractionsGameTest extends com.imdomestic.chorus.test.NoDistractionsGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:no_distractions_normal", maxTicks=40) @Override
    public void normalVariantChangesRealDamageFlinchAndSurvivesActualWeaponFire(GameTestHelper h) throws Exception { super.normalVariantChangesRealDamageFlinchAndSurvivesActualWeaponFire(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:no_distractions_enhanced", maxTicks=40) @Override
    public void enhancedVariantUsesExplicitCalibrationAndSurvivesActualWeaponFire(GameTestHelper h) throws Exception { super.enhancedVariantUsesExplicitCalibrationAndSurvivesActualWeaponFire(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:no_distractions_rearm", maxTicks=50) @Override
    public void acceptedEarlyFireRestartsTheWaitButRejectedFireDoesNot(GameTestHelper h) throws Exception { super.acceptedEarlyFireRestartsTheWaitButRejectedFireDoesNot(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:no_distractions_equipment", maxTicks=65) @Override
    public void armorAndHeldWeaponChangesResampleResistanceWithoutBorrowingStowedPerks(GameTestHelper h) throws Exception { super.armorAndHeldWeaponChangesResampleResistanceWithoutBorrowingStowedPerks(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:no_distractions_absorption") @Override
    public void absorptionLossFlinchesWhileUndeclaredNativeDamageDoesNotInventAngles(GameTestHelper h) throws Exception { super.absorptionLossFlinchesWhileUndeclaredNativeDamageDoesNotInventAngles(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:no_distractions_unknown") @Override
    public void unknownFlinchRetainsPriorDamageAndRotationWithoutReplayingEither(GameTestHelper h) throws Exception { super.unknownFlinchRetainsPriorDamageAndRotationWithoutReplayingEither(h); }
}
