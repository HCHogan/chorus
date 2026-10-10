package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponReloadGameTest extends com.imdomestic.chorus.test.WeaponReloadGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:instant_reload", maxTicks=14) @Override
    public void ordinaryAbilityCommandReloadsOwnedWeaponsAtomicallyAndCancelsTheManualTimer(GameTestHelper h) throws Exception { super.ordinaryAbilityCommandReloadsOwnedWeaponsAtomicallyAndCancelsTheManualTimer(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:instant_reload_liveness") @Override
    public void effectReloadRejectsPhysicallyIneligibleRecipients(GameTestHelper h) throws Exception { super.effectReloadRejectsPhysicallyIneligibleRecipients(h); }

    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:dual_loader", maxTicks=25) @Override
    public void physicalDualLoaderRollsInsertTwoOrThreeRoundsAndClipTheLastInsertion(GameTestHelper h) throws Exception { super.physicalDualLoaderRollsInsertTwoOrThreeRoundsAndClipTheLastInsertion(h); }

    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_reload_incremental", maxTicks=25) @Override
    public void ordinaryReloadCommandLoadsOneRoundPerDeadlineAndActivatesPerkOnFirstInsertion(GameTestHelper h) throws Exception { super.ordinaryReloadCommandLoadsOneRoundPerDeadlineAndActivatesPerkOnFirstInsertion(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_reload_insert_fire", maxTicks=25) @Override
    public void ordinaryFireCommandInterruptsRemainingInsertionsAndKeepsTransferredAmmunition(GameTestHelper h) throws Exception { super.ordinaryFireCommandInterruptsRemainingInsertionsAndKeepsTransferredAmmunition(h); }

    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weapon_reload_complete", maxTicks = 14) @Override
    public void ordinaryPlayerCommandCompletesOwnedWeaponReloadAndActivatesItsPerk(GameTestHelper h) throws Exception { super.ordinaryPlayerCommandCompletesOwnedWeaponReloadAndActivatesItsPerk(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weapon_reload_cancel", maxTicks = 14) @Override
    public void physicalWeaponSwitchCancelsWithoutRefillOrRestartOnRedraw(GameTestHelper h) throws Exception { super.physicalWeaponSwitchCancelsWithoutRefillOrRestartOnRedraw(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weapon_reload_liveness", maxTicks = 14) @Override
    public void deathSpectatorAndRemovedOwnerCannotFinishAnAcceptedReload(GameTestHelper h) throws Exception { super.deathSpectatorAndRemovedOwnerCannotFinishAnAcceptedReload(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weapon_reload_failure", maxTicks = 14) @Override
    public void unknownCompletionReactionKeepsTransferredAmmoAndCannotReplay(GameTestHelper h) throws Exception { super.unknownCompletionReactionKeepsTransferredAmmoAndCannotReplay(h); }
}
