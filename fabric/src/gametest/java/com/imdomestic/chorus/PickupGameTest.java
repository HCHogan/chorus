package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PickupGameTest extends com.imdomestic.chorus.test.PickupGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:pickup_private", maxTicks=60) @Override
    public void onlyNamedCollectorReceivesEachUnitAfterProducerUnequips(GameTestHelper h) throws Exception { super.onlyNamedCollectorReceivesEachUnitAfterProducerUnequips(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:pickup_attraction", maxTicks=60) @Override
    public void attractionReadsCurrentCollectorModifiersInsteadOfProducerSnapshot(GameTestHelper h) throws Exception { super.attractionReadsCurrentCollectorModifiersInsteadOfProducerSnapshot(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:pickup_expiry", maxTicks=60) @Override
    public void expiryRunsOnceWithoutAwardOrPickupFact(GameTestHelper h) throws Exception { super.expiryRunsOnceWithoutAwardOrPickupFact(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:pickup_wall", maxTicks=60) @Override
    public void liveAttractionCannotCrossSolidWallAndRemovalStopsMovement(GameTestHelper h) throws Exception { super.liveAttractionCannotCrossSolidWallAndRemovalStopsMovement(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void missingGeometryRecipientAndUnloadedChunkRejectSpawnAndClosedRuntimeDiscardsObjects(GameTestHelper h) throws Exception { super.missingGeometryRecipientAndUnloadedChunkRejectSpawnAndClosedRuntimeDiscardsObjects(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:pickup_failure", maxTicks=60) @Override
    public void unknownRewardOutcomeConsumesUnitAndNeverReplaysCommittedHealing(GameTestHelper h) throws Exception { super.unknownRewardOutcomeConsumesUnitAndNeverReplaysCommittedHealing(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:pickup_dead", maxTicks=60) @Override
    public void deadRecipientNeitherCollectsExistingUnitNorAcceptsNewSpawn(GameTestHelper h) throws Exception { super.deadRecipientNeitherCollectsExistingUnitNorAcceptsNewSpawn(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:pickup_player", maxTicks=60) @Override
    public void nativePlayerCollectsItsOwnPrivateUnit(GameTestHelper h) throws Exception { super.nativePlayerCollectsItsOwnPrivateUnit(h); }
}
