package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FailDeadlyGameTest extends com.imdomestic.chorus.test.FailDeadlyGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:fail_deadly_stats", maxTicks = 95) @Override
    public void physicalDamageExpandsBothVariantsWithoutRefillingAndProgressOutlivesReceiptHistory(GameTestHelper h) throws Exception { super.physicalDamageExpandsBothVariantsWithoutRefillingAndProgressOutlivesReceiptHistory(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:fail_deadly_rocket", maxTicks = 20) @Override
    public void twoDistinctDamageReceiptsFromOnePhysicalRocketTaggedImpactReachTheTwoStackCap(GameTestHelper h) throws Exception { super.twoDistinctDamageReceiptsFromOnePhysicalRocketTaggedImpactReachTheTwoStackCap(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:fail_deadly_bipod", maxTicks = 30) @Override
    public void bipodCalibratedBaseUsesThreeRoundsAtTwoInstancesAndFourAtFourInstances(GameTestHelper h) throws Exception { super.bipodCalibratedBaseUsesThreeRoundsAtTwoInstancesAndFourAtFourInstances(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:fail_deadly_overflow", maxTicks = 55) @Override
    public void physicalBrickUsesExpandedCapacityAndKillResetsBonusesWithoutDeletingOverflowOrReplayingOldFacts(GameTestHelper h) throws Exception { super.physicalBrickUsesExpandedCapacityAndKillResetsBonusesWithoutDeletingOverflowOrReplayingOldFacts(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:fail_deadly_shells", maxTicks = 50) @Override
    public void fullShotgunProgressChangesEachActualInsertionAndKeepsReserveConservation(GameTestHelper h) throws Exception { super.fullShotgunProgressChangesEachActualInsertionAndKeepsReserveConservation(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:fail_deadly_detach", maxTicks = 45) @Override
    public void stowAllowsLatePhysicalHitsButActualPerkReplacementClearsProgressAndEligibility(GameTestHelper h) throws Exception { super.stowAllowsLatePhysicalHitsButActualPerkReplacementClearsProgressAndEligibility(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:fail_deadly_unknown", maxTicks = 20) @Override
    public void unknownDamageOutcomeKeepsActualHealthLossWithoutInventingProgressOrReplayingDamage(GameTestHelper h) throws Exception { super.unknownDamageOutcomeKeepsActualHealthLossWithoutInventingProgressOrReplayingDamage(h); }
}
