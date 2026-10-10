package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class VoltshotWeaponGameTest extends com.imdomestic.chorus.test.VoltshotWeaponGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:voltshot_weapon_cycle", maxTicks = 70) @Override
    public void ownedKillReloadAndStowedFlightApplyJoltAndItsKillCannotRefreshTheWeaponWindow(GameTestHelper h) throws Exception { super.ownedKillReloadAndStowedFlightApplyJoltAndItsKillCannotRefreshTheWeaponWindow(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:voltshot_weapon_cancel", maxTicks = 35) @Override
    public void stowingBeforeReloadCompletesPreservesTheKillWindowButDoesNotArm(GameTestHelper h) throws Exception { super.stowingBeforeReloadCompletesPreservesTheKillWindowButDoesNotArm(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:voltshot_weapon_credit", maxTicks = 40) @Override
    public void actualWeaponKillWithoutDeclaredCreditAndRealRefillDoNotActivateVoltshot(GameTestHelper h) throws Exception { super.actualWeaponKillWithoutDeclaredCreditAndRealRefillDoNotActivateVoltshot(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:voltshot_weapon_unknown", maxTicks = 40) @Override
    public void unknownStatusAfterChargedProjectileKeepsAmmoDamageAndSpentChargeWithoutReplay(GameTestHelper h) throws Exception { super.unknownStatusAfterChargedProjectileKeepsAmmoDamageAndSpentChargeWithoutReplay(h); }
}
