package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WitheringBladeGameTest extends com.imdomestic.chorus.test.WitheringBladeGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void fourthPhysicalTargetReceivesDamageAndSlowBeforeFlightEnds(GameTestHelper h)throws Exception{super.fourthPhysicalTargetReceivesDamageAndSlowBeforeFlightEnds(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void twoUnequippedBladesApplyCurrentDuranceThenFreezeWithFinalCastCredit(GameTestHelper h)throws Exception{super.twoUnequippedBladesApplyCurrentDuranceThenFreezeWithFinalCastCredit(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualGuardianReceivesSeventyTwoDamageAndFortySlowEvenInPve(GameTestHelper h)throws Exception{super.actualGuardianReceivesSeventyTwoDamageAndFortySlowEvenInPve(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:withering_recovery",maxTicks=80) @Override
    public void realTicksRechargeOneSharedAccountWhileEmptyFlightsExpire(GameTestHelper h)throws Exception{super.realTicksRechargeOneSharedAccountWhileEmptyFlightsExpire(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownRealDamageKeepsChargeSpentAndCannotApplySlowOrRepeatHit(GameTestHelper h)throws Exception{super.unknownRealDamageKeepsChargeSpentAndCannotApplySlowOrRepeatHit(h);}
}
