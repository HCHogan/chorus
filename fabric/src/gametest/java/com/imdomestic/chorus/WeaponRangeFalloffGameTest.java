package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponRangeFalloffGameTest extends com.imdomestic.chorus.test.WeaponRangeFalloffGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:range_auto",maxTicks=35) @Override
    public void autoRifleActualCollisionUsesItsPlateauInterpolationAndNonzeroFloor(GameTestHelper h)throws Exception {super.autoRifleActualCollisionUsesItsPlateauInterpolationAndNonzeroFloor(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:range_hand_cannon",maxTicks=35) @Override
    public void handCannonUsesDifferentEndpointsAndThirtyThreePercentFloor(GameTestHelper h)throws Exception {super.handCannonUsesDifferentEndpointsAndThirtyThreePercentFloor(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:range_ads",maxTicks=30) @Override
    public void readyAdsRangeSurvivesReleaseUnequipAndOwnerMovementBeforeThePhysicalHit(GameTestHelper h)throws Exception {super.readyAdsRangeSurvivesReleaseUnequipAndOwnerMovementBeforeThePhysicalHit(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:range_fail_deadly",maxTicks=35) @Override
    public void actualFailDeadlyHitExtendsTheFollowingWeaponsDamagePlateau(GameTestHelper h)throws Exception {super.actualFailDeadlyHitExtendsTheFollowingWeaponsDamagePlateau(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:range_pierce",maxTicks=25) @Override
    public void piercingContactsUseSeparateDistancesFromTheSameCapturedMuzzle(GameTestHelper h)throws Exception {super.piercingContactsUseSeparateDistancesFromTheSameCapturedMuzzle(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:range_unknown",maxTicks=25) @Override
    public void unknownReceiptRetainsAppliedFalloffDamageWithoutReplayingContact(GameTestHelper h)throws Exception {super.unknownReceiptRetainsAppliedFalloffDamageWithoutReplayingContact(h);}
}
