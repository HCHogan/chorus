package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ProjectileCatchGameTest extends com.imdomestic.chorus.test.ProjectileCatchGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void networkRoundTripCatchesDetachedReturnAndDuplicateCannotRunActionsAgain(GameTestHelper h) throws Exception { super.networkRoundTripCatchesDetachedReturnAndDuplicateCannotRunActionsAgain(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void windowAndDistanceAreCheckedAtInputAndMissedInputDoesNotLatch(GameTestHelper h) throws Exception { super.windowAndDistanceAreCheckedAtInputAndMissedInputDoesNotLatch(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void receiverIdentityPlayerStateAndDimensionAreServerOwned(GameTestHelper h) throws Exception { super.receiverIdentityPlayerStateAndDimensionAreServerOwned(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void lineOfSightUsesWorldGeometryUnlessExplicitlyDisabled(GameTestHelper h) throws Exception { super.lineOfSightUsesWorldGeometryUnlessExplicitlyDisabled(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void eachInputConsumesOneNearestFlightWithDeterministicTies(GameTestHelper h) throws Exception { super.eachInputConsumesOneNearestFlightWithDeterministicTies(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void replacingRuntimeCannotCatchFlightsOwnedByPreviousInstance(GameTestHelper h) throws Exception { super.replacingRuntimeCannotCatchFlightsOwnedByPreviousInstance(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownCatchHealingKeepsRefundAndConsumesFlightAndInputWithoutReplay(GameTestHelper h) throws Exception { super.unknownCatchHealingKeepsRefundAndConsumesFlightAndInputWithoutReplay(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void projectilesWithoutDeclaredCatchRemainOrdinaryFlights(GameTestHelper h) throws Exception { super.projectilesWithoutDeclaredCatchRemainOrdinaryFlights(h); }
}
