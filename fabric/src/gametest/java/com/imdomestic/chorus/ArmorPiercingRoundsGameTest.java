package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ArmorPiercingRoundsGameTest extends com.imdomestic.chorus.test.ArmorPiercingRoundsGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ap_layers",maxTicks=100) @Override
    public void oneRoundPiercesTwoTargetsAndOnlyTheEligibleShieldGetsTheBonus(GameTestHelper h)throws Exception{super.oneRoundPiercesTwoTargetsAndOnlyTheEligibleShieldGetsTheBonus(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ap_range",maxTicks=100) @Override
    public void ordinaryAndEnhancedRangeChangeActualFalloffIndependentlyAtEachContact(GameTestHelper h)throws Exception{super.ordinaryAndEnhancedRangeChangeActualFalloffIndependentlyAtEachContact(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ap_equipment",maxTicks=20) @Override
    public void switchingToTheEnhancedWeaponChangesRangeAndPenetrationWithoutBorrowingItsPerk(GameTestHelper h)throws Exception{super.switchingToTheEnhancedWeaponChangesRangeAndPenetrationWithoutBorrowingItsPerk(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ap_barrier",maxTicks=100) @Override
    public void barrierBonusDoesNotAuthorizeDamageThroughAnImmuneLayer(GameTestHelper h)throws Exception{super.barrierBonusDoesNotAuthorizeDamageThroughAnImmuneLayer(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ap_wall",maxTicks=100) @Override
    public void terrainStillStopsThePerkAfterTheFirstTarget(GameTestHelper h)throws Exception{super.terrainStillStopsThePerkAfterTheFirstTarget(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ap_unknown",maxTicks=100) @Override
    public void unknownShieldOverflowRetainsOnePaymentAndDoesNotReplayOrDamageTheNextTarget(GameTestHelper h)throws Exception{super.unknownShieldOverflowRetainsOnePaymentAndDoesNotReplayOrDamageTheNextTarget(h);}
}
