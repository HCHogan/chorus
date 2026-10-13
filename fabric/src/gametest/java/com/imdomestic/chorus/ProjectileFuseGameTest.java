package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProjectileFuseGameTest extends com.imdomestic.chorus.test.ProjectileFuseGameTest {
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_endpoint") @Override
    public void continuingContactExactlyAtRangeEndsAtThatPointEvenWhenBounceStopsAllMotion(GameTestHelper h) throws Exception { super.continuingContactExactlyAtRangeEndsAtThatPointEvenWhenBounceStopsAllMotion(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_range") @Override
    public void rangeClipsFastFlightBeforeFartherCollisionAndRunsOnlyOnce(GameTestHelper h) throws Exception { super.rangeClipsFastFlightBeforeFartherCollisionAndRunsOnlyOnce(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_bounce") @Override
    public void bounceAndPiercingConsumeOneCumulativeRangeAcrossContactSegments(GameTestHelper h) throws Exception { super.bounceAndPiercingConsumeOneCumulativeRangeAcrossContactSegments(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_curve") @Override
    public void gravityAndDragUseActualSegmentLengthsInsteadOfInitialSpeedTimesAge(GameTestHelper h) throws Exception { super.gravityAndDragUseActualSegmentLengthsInsteadOfInitialSpeedTimesAge(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_fast") @Override
    public void highSpeedProximitySweepFindsFirstSphereEntryWithoutPhysicalContact(GameTestHelper h) throws Exception { super.highSpeedProximitySweepFindsFirstSphereEntryWithoutPhysicalContact(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_arm") @Override
    public void armingInsideSphereExplodesThereAndArmingAfterPassingDoesNotRewind(GameTestHelper h) throws Exception { super.armingInsideSphereExplodesThereAndArmingAfterPassingDoesNotRewind(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_wall") @Override
    public void physicalWallBeforeFuseWinsButFartherWallCannotOverrideEarlierFuse(GameTestHelper h) throws Exception { super.physicalWallBeforeFuseWinsButFartherWallCannotOverrideEarlierFuse(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_sight") @Override
    public void sideWallRequiresLineOfSightOnlyWhenRequested(GameTestHelper h) throws Exception { super.sideWallRequiresLineOfSightOnlyWhenRequested(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_relation") @Override
    public void alliedTargetsAreSkippedAndMissingOwnerDoesNotInventRelativeRelation(GameTestHelper h) throws Exception { super.alliedTargetsAreSkippedAndMissingOwnerDoesNotInventRelativeRelation(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_order") @Override
    public void directCollisionAndRangeCanEachPrecedeAnArmedProximityTrigger(GameTestHelper h) throws Exception { super.directCollisionAndRangeCanEachPrecedeAnArmedProximityTrigger(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_stationary") @Override
    public void stationaryArmedOverlapCanTriggerButUnarmedStationaryFlightOnlyExpires(GameTestHelper h) throws Exception { super.stationaryArmedOverlapCanTriggerButUnarmedStationaryFlightOnlyExpires(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_unknown") @Override
    public void unknownTerminalCallbackConsumesFlightAndCannotReplayOnLaterTicks(GameTestHelper h) throws Exception { super.unknownTerminalCallbackConsumesFlightAndCannotReplayOnLaterTicks(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_identity") @Override
    public void equalEntryUsesStableIdentityAndDeadTargetsCannotTriggerAgain(GameTestHelper h) throws Exception { super.equalEntryUsesStableIdentityAndDeadTargetsCannotTriggerAgain(h); }
    @GameTest(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_prefix") @Override
    public void fuseBeforeInactiveTerrainCompletesWithoutScanningOrEnteringTheUnavailableSuffix(GameTestHelper h) throws Exception { super.fuseBeforeInactiveTerrainCompletesWithoutScanningOrEnteringTheUnavailableSuffix(h); }
}
