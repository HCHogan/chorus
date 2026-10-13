package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PickupCollectionGameTest extends com.imdomestic.chorus.test.PickupCollectionGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void remoteClaimsKeepPrivateOwnershipCapturedRewardsAndOrdinaryPickupFacts(GameTestHelper h) throws Exception { super.remoteClaimsKeepPrivateOwnershipCapturedRewardsAndOrdinaryPickupFacts(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void firstRewardFillsTheGapBeforeTheSecondPhysicalCandidateChecksEligibility(GameTestHelper h) throws Exception { super.firstRewardFillsTheGapBeforeTheSecondPhysicalCandidateChecksEligibility(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void everyClaimRechecksRangeVisibilityPresenceAndTheExactObject(GameTestHelper h) throws Exception { super.everyClaimRechecksRangeVisibilityPresenceAndTheExactObject(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void duplicateCandidatesAcrossRequestsCannotConsumeOnePhysicalUnitTwice(GameTestHelper h) throws Exception { super.duplicateCandidatesAcrossRequestsCannotConsumeOnePhysicalUnitTwice(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownRewardConsumesOnlyTheClaimedUnitAndStopsTheRemainingBatch(GameTestHelper h) throws Exception { super.unknownRewardConsumesOnlyTheClaimedUnitAndStopsTheRemainingBatch(h); }
}
