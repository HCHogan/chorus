package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeConsumptionGameTest extends com.imdomestic.chorus.test.NativeConsumptionGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void ordinaryNativeHitsConsumeOnceAndPublishDamageBeforeLifecycle(GameTestHelper h) throws Exception { super.ordinaryNativeHitsConsumeOnceAndPublishDamageBeforeLifecycle(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void cancelledNativeHitReleasesItsReservation(GameTestHelper h) throws Exception { super.cancelledNativeHitReleasesItsReservation(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeImmunityUsesTheDeclaredConsumptionPolicy(GameTestHelper h) throws Exception { super.nativeImmunityUsesTheDeclaredConsumptionPolicy(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void independentNestedNativeHitsReserveOnlyOneStack(GameTestHelper h) throws Exception { super.independentNestedNativeHitsReserveOnlyOneStack(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void cancelledParentKeepsItsChargeAfterNestedChildConsumes(GameTestHelper h) throws Exception { super.cancelledParentKeepsItsChargeAfterNestedChildConsumes(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void managedParentAndNativeChildDoNotConsumeAgainOnActionComplete(GameTestHelper h) throws Exception { super.managedParentAndNativeChildDoNotConsumeAgainOnActionComplete(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void explicitNestedGroupSharesOnePendingAndConfirmedEntitlement(GameTestHelper h) throws Exception { super.explicitNestedGroupSharesOnePendingAndConfirmedEntitlement(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownParentRetainsConfirmedChildAndPendingReservationWithoutReplay(GameTestHelper h) throws Exception { super.unknownParentRetainsConfirmedChildAndPendingReservationWithoutReplay(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void lostManagedReceiptRetainsKnownConsumptionAndPendingPureOperation(GameTestHelper h) throws Exception { super.lostManagedReceiptRetainsKnownConsumptionAndPendingPureOperation(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void absorptionOnlyNativeHitCountsAsEffectiveDamage(GameTestHelper h) throws Exception { super.absorptionOnlyNativeHitCountsAsEffectiveDamage(h); }
}
