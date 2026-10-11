package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ReconstructionGameTest extends com.imdomestic.chorus.test.ReconstructionGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:reconstruction_cadence", maxTicks = 390) @Override
    public void realTicksFillNormalAndEnhancedWeaponsWhileStowedUpToDoubleWithConservedReserves(GameTestHelper h) throws Exception { super.realTicksFillNormalAndEnhancedWeaponsWhileStowedUpToDoubleWithConservedReserves(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:reconstruction_fire", maxTicks = 370) @Override
    public void realAcceptedFireRestartsOnlyItsWeaponClockAndCooldownRejectionDoesNot(GameTestHelper h) throws Exception { super.realAcceptedFireRestartsOnlyItsWeaponClockAndCooldownRejectionDoesNot(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:reconstruction_reload", maxTicks = 150) @Override
    public void actualManualReloadSpendsReservesButDoesNotPostponeTheStoppedFireTimer(GameTestHelper h) throws Exception { super.actualManualReloadSpendsReservesButDoesNotPostponeTheStoppedFireTimer(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:reconstruction_replace", maxTicks = 370) @Override
    public void actualSocketReplacementCancelsOldCadenceAndRemovalRetainsLoadedAmmo(GameTestHelper h) throws Exception { super.actualSocketReplacementCancelsOldCadenceAndRemovalRetainsLoadedAmmo(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:reconstruction_unknown", maxTicks = 280) @Override
    public void unknownAutomaticRefillReactionRetainsOneTransferWithoutReplaying(GameTestHelper h) throws Exception { super.unknownAutomaticRefillReactionRetainsOneTransferWithoutReplaying(h); }
}
