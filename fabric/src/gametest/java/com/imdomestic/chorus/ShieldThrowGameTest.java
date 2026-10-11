package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ShieldThrowGameTest extends com.imdomestic.chorus.test.ShieldThrowGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualPaidThrowHitsFiveTargetsUnderExplicitContinuationCalibrationAndCapsSharedShield(GameTestHelper h) throws Exception { super.actualPaidThrowHitsFiveTargetsUnderExplicitContinuationCalibrationAndCapsSharedShield(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualGuardianReceivesSeventyDamageAndThrowerReceivesFifteenShield(GameTestHelper h) throws Exception { super.actualGuardianReceivesSeventyDamageAndThrowerReceivesFifteenShield(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeDamageClassifiesActualCreditOwnerAndOnlyCombatantShieldBudgetGetsReduction(GameTestHelper h) throws Exception { super.nativeDamageClassifiesActualCreditOwnerAndOnlyCombatantShieldBudgetGetsReduction(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void otherShieldLayersKeepFifoOrderAndRecreatedVoidPoolGetsANewAge(GameTestHelper h) throws Exception { super.otherShieldLayersKeepFifoOrderAndRecreatedVoidPoolGetsANewAge(h); }
    @GameTest(structure = "chorus_gametest:empty", environment="chorus_gametest:void_shield_expiry",maxTicks=30) @Override
    public void realTicksExpireASourceProvidedWindowAfterItsGrantorIsUnbound(GameTestHelper h) throws Exception { super.realTicksExpireASourceProvidedWindowAfterItsGrantorIsUnbound(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualWallBounceAndGravityDoNotCountAsAHitGrant(GameTestHelper h) throws Exception { super.actualWallBounceAndGravityDoNotCountAsAHitGrant(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownRealDamageRetainsPaymentAndHealthLossWithoutGrantingShieldOrRepeating(GameTestHelper h) throws Exception { super.unknownRealDamageRetainsPaymentAndHealthLossWithoutGrantingShieldOrRepeating(h); }
}
