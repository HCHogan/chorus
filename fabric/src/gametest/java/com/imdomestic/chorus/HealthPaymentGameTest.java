package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class HealthPaymentGameTest extends com.imdomestic.chorus.test.HealthPaymentGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:health_payment") @Override
    public void nativeHealthPaymentBypassesDefensesWithoutHitsDeathsOrTotemConsumption(GameTestHelper h){super.nativeHealthPaymentBypassesDefensesWithoutHitsDeathsOrTotemConsumption(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:health_payment_precision") @Override
    public void floatQuantizationNeverOverchargesAndZeroOrBelowFloorPaymentsNeverHeal(GameTestHelper h){super.floatQuantizationNeverOverchargesAndZeroOrBelowFloorPaymentsNeverHeal(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:health_payment_missing") @Override
    public void deadAndMissingTargetsCannotPayOrRunSuccessBranches(GameTestHelper h){super.deadAndMissingTargetsCannotPayOrRunSuccessBranches(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:health_payment_fault") @Override
    public void unknownReceiptKeepsCommittedHealthAndNeverRepeatsTheWorldWrite(GameTestHelper h){super.unknownReceiptKeepsCommittedHealthAndNeverRepeatsTheWorldWrite(h);}
}
