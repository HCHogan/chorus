package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ConditionalPickupGameTest extends com.imdomestic.chorus.test.ConditionalPickupGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:conditional_pickup_player", maxTicks=60) @Override
    public void fullNativePlayerCannotCollectUntilManualReloadConsumesReserves(GameTestHelper h) throws Exception { super.fullNativePlayerCannotCollectUntilManualReloadConsumesReserves(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:conditional_pickup_loadout", maxTicks=60) @Override
    public void lateLoadoutChangeRoutesRewardToCurrentStowedWeaponsNotProducerOrOldAccounts(GameTestHelper h) throws Exception { super.lateLoadoutChangeRoutesRewardToCurrentStowedWeaponsNotProducerOrOldAccounts(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:conditional_pickup_compete", maxTicks=60) @Override
    public void firstBrickFillsGapAndSecondWaitsForAnotherEligibleWeapon(GameTestHelper h) throws Exception { super.firstBrickFillsGapAndSecondWaitsForAnotherEligibleWeapon(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:conditional_pickup_expire", maxTicks=60) @Override
    public void infiniteReservesDoNotQualifyAndRejectedClaimStillExpiresNormally(GameTestHelper h) throws Exception { super.infiniteReservesDoNotQualifyAndRejectedClaimStillExpiresNormally(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:conditional_pickup_failure", maxTicks=60) @Override
    public void rewardFailureKeepsCommittedReservesAndConsumesClaimWithoutPickupReactionReplay(GameTestHelper h) throws Exception { super.rewardFailureKeepsCommittedReservesAndConsumesClaimWithoutPickupReactionReplay(h); }
}
