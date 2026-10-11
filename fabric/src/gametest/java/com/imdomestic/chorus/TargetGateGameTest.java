package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class TargetGateGameTest extends com.imdomestic.chorus.test.TargetGateGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void recipientGateSkipsClosestTargetAndActorGatePreventsAllAcquisition(GameTestHelper h) throws Exception { super.recipientGateSkipsClosestTargetAndActorGatePreventsAllAcquisition(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void liveTargetGateDropsLockPreservesFlightAndUsesCapturedSourceAfterRemoval(GameTestHelper h) throws Exception { super.liveTargetGateDropsLockPreservesFlightAndUsesCapturedSourceAfterRemoval(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:target_gate_expiry", maxTicks = 30) @Override
    public void realTicksReleaseExpiredTargetBuffAndReacquireWithoutPermanentBlacklist(GameTestHelper h) throws Exception { super.realTicksReleaseExpiredTargetBuffAndReacquireWithoutPermanentBlacklist(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void trackingImmuneTargetStillReceivesOrdinaryPhysicalImpact(GameTestHelper h) throws Exception { super.trackingImmuneTargetStillReceivesOrdinaryPhysicalImpact(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void contactRedirectSkipsImmuneNeighborAndPreservesThePhysicalPierceBudget(GameTestHelper h) throws Exception { super.contactRedirectSkipsImmuneNeighborAndPreservesThePhysicalPierceBudget(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void invalidTrackingQueryStopsRuntimeAndAbandonsAllOwnedFlightsWithoutReplay(GameTestHelper h) throws Exception { super.invalidTrackingQueryStopsRuntimeAndAbandonsAllOwnedFlightsWithoutReplay(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void explicitReturnDestinationBypassesEnemyTrackingRestrictions(GameTestHelper h) throws Exception { super.explicitReturnDestinationBypassesEnemyTrackingRestrictions(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeMeleeUsesVictimRestrictionWithoutReassigningAttackerCredit(GameTestHelper h) { super.nativeMeleeUsesVictimRestrictionWithoutReassigningAttackerCredit(h); }
}
