package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponFireGameTest extends com.imdomestic.chorus.test.WeaponFireGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weapon_fire_kill_clip", maxTicks = 50) @Override
    public void ownedWeaponShotKillAndReloadActivateKillClipAndNextFlightKeepsItsSnapshotAfterStow(GameTestHelper h) throws Exception { super.ownedWeaponShotKillAndReloadActivateKillClipAndNextFlightKeepsItsSnapshotAfterStow(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weapon_fire_credit", maxTicks = 20) @Override
    public void weaponOriginWithoutDeclaredKillCreditDoesNotActivateWeaponKillPerk(GameTestHelper h) throws Exception { super.weaponOriginWithoutDeclaredKillCreditDoesNotActivateWeaponKillPerk(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weapon_fire_liveness", maxTicks = 20) @Override
    public void ineligibleOwnersCannotShootAndAcceptedFireInterruptsPhysicalReload(GameTestHelper h) throws Exception { super.ineligibleOwnersCannotShootAndAcceptedFireInterruptsPhysicalReload(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weapon_fire_unknown", maxTicks = 20) @Override
    public void unknownPhysicalLaunchKeepsCostAndStopsFlightWithoutReplayingShot(GameTestHelper h) throws Exception { super.unknownPhysicalLaunchKeepsCostAndStopsFlightWithoutReplayingShot(h); }
}
