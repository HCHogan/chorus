package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PhoenixDiveGameTest extends com.imdomestic.chorus.test.PhoenixDiveGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:phoenix_landing", maxTicks = 100) @Override
    public void paidInputWaitsForAcceptedPhysicalLandingBeforeSharedCureAndBenevolence(GameTestHelper h) { super.paidInputWaitsForAcceptedPhysicalLandingBeforeSharedCureAndBenevolence(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:phoenix_heat", maxTicks = 100) @Override
    public void immediateRestorationAndHeatedLandingRetainClassCreditThroughSharedScorch(GameTestHelper h) { super.immediateRestorationAndHeatedLandingRetainClassCreditThroughSharedScorch(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:phoenix_unequip", maxTicks = 100) @Override
    public void unequippingAfterPaymentRetainsOneLandingAndItsOriginalAbilityCredit(GameTestHelper h) { super.unequippingAfterPaymentRetainsOneLandingAndItsOriginalAbilityCredit(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:phoenix_death", maxTicks = 100) @Override
    public void confirmedPlayerDeathCancelsThePendingDive(GameTestHelper h) { super.confirmedPlayerDeathCancelsThePendingDive(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:phoenix_removal", maxTicks = 100) @Override
    public void confirmedPlayerRemovalCancelsThePendingDive(GameTestHelper h) { super.confirmedPlayerRemovalCancelsThePendingDive(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:phoenix_unknown_blast", maxTicks = 100) @Override
    public void unknownBlastReceiptRetainsNativeDamageAndConsumedLandingWithoutReplay(GameTestHelper h) { super.unknownBlastReceiptRetainsNativeDamageAndConsumedLandingWithoutReplay(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:phoenix_unknown_cure", maxTicks = 100) @Override
    public void unknownCureReceiptRetainsTheFirstActualPulseWithoutInventingFragmentCredit(GameTestHelper h) { super.unknownCureReceiptRetainsTheFirstActualPulseWithoutInventingFragmentCredit(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:phoenix_repeat", maxTicks = 100) @Override
    public void aSubsequentPaidDiveCanLandAgainAfterTheFirstPendingIsConsumed(GameTestHelper h) { super.aSubsequentPaidDiveCanLandAgainAfterTheFirstPendingIsConsumed(h); }
}
