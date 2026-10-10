package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProjectileDestinationGameTest extends com.imdomestic.chorus.test.ProjectileDestinationGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:projectile_return", maxTicks = 40) @Override
    public void realTicksReturnFromImpactToMovingCasterAndRefundOnlyAfterArrival(GameTestHelper h) throws Exception { super.realTicksReturnFromImpactToMovingCasterAndRefundOnlyAfterArrival(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void highSpeedSweepFindsArrivalBeforeCrossingReceiverOrGeometryBeyondIt(GameTestHelper h) throws Exception { super.highSpeedSweepFindsArrivalBeforeCrossingReceiverOrGeometryBeyondIt(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void wallBeforeArrivalStopsReturnWithoutRefunding(GameTestHelper h) throws Exception { super.wallBeforeArrivalStopsReturnWithoutRefunding(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void otherLivingEntitiesBlockOrAllowReturnAccordingToExplicitCollisionPolicy(GameTestHelper h) throws Exception { super.otherLivingEntitiesBlockOrAllowReturnAccordingToExplicitCollisionPolicy(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void missingOrDeadDestinationEndsWithoutAcquiringNearbyReplacement(GameTestHelper h) throws Exception { super.missingOrDeadDestinationEndsWithoutAcquiringNearbyReplacement(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void destinationSteeringUsesAngularBudgetAndStationaryOverlapStillArrives(GameTestHelper h) throws Exception { super.destinationSteeringUsesAngularBudgetAndStationaryOverlapStillArrives(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void explicitLoopBoundReceiverIsCapturedAndArrivalDoesNotStrikeItAgain(GameTestHelper h) throws Exception { super.explicitLoopBoundReceiverIsCapturedAndArrivalDoesNotStrikeItAgain(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownArrivalHealingKeepsSpentFlightAndCommittedRefundWithoutReplay(GameTestHelper h) throws Exception { super.unknownArrivalHealingKeepsSpentFlightAndCommittedRefundWithoutReplay(h); }
}
