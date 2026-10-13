package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FusionGrenadeGameTest extends com.imdomestic.chorus.test.FusionGrenadeGameTest {
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_surface", maxTicks = 100) @Override
    public void paidSurfaceImpactWaitsThenSelectsCurrentVisibleEnemiesWithRadialDamage(GameTestHelper h) { super.paidSurfaceImpactWaitsThenSelectsCurrentVisibleEnemiesWithRadialDamage(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_touch", maxTicks = 100) @Override
    public void capturedTouchAshesAndUnequipRetainTheSecondBlastWithoutApplyingScorchTwice(GameTestHelper h) { super.capturedTouchAshesAndUnequipRetainTheSecondBlastWithoutApplyingScorchTwice(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_move", maxTicks = 100) @Override
    public void attachedPhysicalContactFollowsBothMovesAndEachExplosionQueriesNewNeighbors(GameTestHelper h) { super.attachedPhysicalContactFollowsBothMovesAndEachExplosionQueriesNewNeighbors(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_tracking", maxTicks = 100) @Override
    public void slightPhysicalHomingTurnsTowardAnOffAxisEnemyBeforeAttaching(GameTestHelper h) { super.slightPhysicalHomingTurnsTowardAnOffAxisEnemyBeforeAttaching(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_removed", maxTicks = 100) @Override
    public void confirmedRemovalFixesTheLastPositionAndCannotAttachToTheReplacementLife(GameTestHelper h) { super.confirmedRemovalFixesTheLastPositionAndCannotAttachToTheReplacementLife(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_unloaded", maxTicks = 100) @Override
    public void nativeUnloadEndsThePendingAttachmentBeforeEitherExplosion(GameTestHelper h) { super.nativeUnloadEndsThePendingAttachmentBeforeEitherExplosion(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_death", maxTicks = 100) @Override
    public void firstExplosionKillsAttachedLifeAndSecondStillHitsTheSurroundingWorld(GameTestHelper h) { super.firstExplosionKillsAttachedLifeAndSecondStillHitsTheSurroundingWorld(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_cover", maxTicks = 100) @Override
    public void newlyRaisedCoverExcludesTheRecipientFromOnlyTheSecondBlast(GameTestHelper h) { super.newlyRaisedCoverExcludesTheRecipientFromOnlyTheSecondBlast(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_denied", maxTicks = 100) @Override
    public void deniedStatusRetainsFirstDamageAndDoesNotCancelIndependentSecondExplosion(GameTestHelper h) { super.deniedStatusRetainsFirstDamageAndDoesNotCancelIndependentSecondExplosion(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_unknown", maxTicks = 100) @Override
    public void unknownAppliedBlastDamagePreservesHpLossAndStopsBeforeStatusAndReplay(GameTestHelper h) { super.unknownAppliedBlastDamagePreservesHpLossAndStopsBeforeStatusAndReplay(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_heat", maxTicks = 100) @Override
    public void heldHeatRisesConsumesTheRealFusionChargeWithoutLaunchingAShell(GameTestHelper h) { super.heldHeatRisesConsumesTheRealFusionChargeWithoutLaunchingAShell(h); }
    @GameTest(structure = "chorus_gametest:firebolt", environment = "chorus_gametest:fusion_ignition", maxTicks = 100) @Override
    public void twoPaidPhysicalAshesGrenadesReachIgnitionWithoutCountingTheSecondExplosionAsScorch(GameTestHelper h) { super.twoPaidPhysicalAshesGrenadesReachIgnitionWithoutCountingTheSecondExplosionAsScorch(h); }
}
