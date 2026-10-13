package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class MarksmanAmmoGameTest extends com.imdomestic.chorus.test.MarksmanAmmoGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:marksman_ammo_reload", maxTicks=16) @Override
    public void fifteenMeterSpecialAndHeavyBricksSupplyTheActualDelayedReload(GameTestHelper h) throws Exception { super.fifteenMeterSpecialAndHeavyBricksSupplyTheActualDelayedReload(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:marksman_ammo_full", maxTicks=16) @Override
    public void fullReservesKeepTheRemoteBrickEvenWhenTheLaterReloadCreatesSpace(GameTestHelper h) throws Exception { super.fullReservesKeepTheRemoteBrickEvenWhenTheLaterReloadCreatesSpace(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:marksman_ammo_capacity", maxTicks=16) @Override
    public void multipleBricksStopAtCapacityAndAnExhaustedSecondCastCannotCollectAgain(GameTestHelper h) throws Exception { super.multipleBricksStopAtCapacityAndAnExhaustedSecondCastCannotCollectAgain(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:marksman_ammo_overflow", maxTicks=16) @Override
    public void equippedOverflowSettlesBeforeTheNextBrickAndTheDodgeReloadPreservesOverfilledMagazines(GameTestHelper h) throws Exception { super.equippedOverflowSettlesBeforeTheNextBrickAndTheDodgeReloadPreservesOverfilledMagazines(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:marksman_ammo_private", maxTicks=16) @Override
    public void privateBricksOfAnotherRecipientCannotBeTakenByTheDodge(GameTestHelper h) throws Exception { super.privateBricksOfAnotherRecipientCannotBeTakenByTheDodge(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:marksman_ammo_ascension", maxTicks=16) @Override
    public void ascensionInvokesTheSamePickupBenefitAtItsCurrentPositionAndRetainsTheCapturedDodge(GameTestHelper h) throws Exception { super.ascensionInvokesTheSamePickupBenefitAtItsCurrentPositionAndRetainsTheCapturedDodge(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:marksman_ammo_unknown", maxTicks=16) @Override
    public void unknownPickupReactionRetainsOneRealRewardAndStopsFurtherClaimsAndReloads(GameTestHelper h) throws Exception { super.unknownPickupReactionRetainsOneRealRewardAndStopsFurtherClaimsAndReloads(h); }
}
