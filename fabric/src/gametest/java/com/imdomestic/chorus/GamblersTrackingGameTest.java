package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class GamblersTrackingGameTest extends com.imdomestic.chorus.test.GamblersTrackingGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:gambler_tracking_expiry", maxTicks = 30) @Override
    public void combatantLockBreaksDuringActualDodgeAndReturnsAfterExpiryDespiteSourceRemoval(GameTestHelper h) throws Exception { super.combatantLockBreaksDuringActualDodgeAndReturnsAfterExpiryDespiteSourceRemoval(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void guardianProjectileKeepsLockThroughActualDodgeRegardlessOfPveMode(GameTestHelper h) throws Exception { super.guardianProjectileKeepsLockThroughActualDodgeRegardlessOfPveMode(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void immuneTrackingStillAllowsPhysicalDamageDuringTheDodgeWindow(GameTestHelper h) throws Exception { super.immuneTrackingStillAllowsPhysicalDamageDuringTheDodgeWindow(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void confirmedDeathClearsTrackingWindowAfterActualLethalImpact(GameTestHelper h) throws Exception { super.confirmedDeathClearsTrackingWindowAfterActualLethalImpact(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unavailableCreditOwnerIsNotInferredFromTheMobThatEmittedTheProjectile(GameTestHelper h) throws Exception { super.unavailableCreditOwnerIsNotInferredFromTheMobThatEmittedTheProjectile(h); }
}
