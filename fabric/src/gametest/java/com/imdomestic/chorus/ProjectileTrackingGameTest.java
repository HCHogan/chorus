package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProjectileTrackingGameTest extends com.imdomestic.chorus.test.ProjectileTrackingGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void turnsAtCapturedAngularRateAndRetainsMovingIdentityInsteadOfSwitchingToNearerEnemy(GameTestHelper h) throws Exception { super.turnsAtCapturedAngularRateAndRetainsMovingIdentityInsteadOfSwitchingToNearerEnemy(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void acquisitionConeAndRadiusFilterTargetsWhileLostLockKeepsBallisticVelocity(GameTestHelper h) throws Exception { super.acquisitionConeAndRadiusFilterTargetsWhileLostLockKeepsBallisticVelocity(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void contactRedirectVisitsThreeDistinctEnemiesInOneTickAndHonorsPierceBudget(GameTestHelper h) throws Exception { super.contactRedirectVisitsThreeDistinctEnemiesInOneTickAndHonorsPierceBudget(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void reflectedFlightCanRedirectTowardEnemyAndApplyLiveBounceDamage(GameTestHelper h) throws Exception { super.reflectedFlightCanRedirectTowardEnemyAndApplyLiveBounceDamage(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void allianceAndLineOfSightAreRecheckedAndMissingOwnerDisablesRelativeTracking(GameTestHelper h) throws Exception { super.allianceAndLineOfSightAreRecheckedAndMissingOwnerDisablesRelativeTracking(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:projectile_tracking", maxTicks = 60) @Override
    public void actualTicksTrackMovingEnemyAndRetainAttackAfterSourcesAreRemoved(GameTestHelper h) throws Exception { super.actualTicksTrackMovingEnemyAndRetainAttackAfterSourcesAreRemoved(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void anyRelationCanReacquireAfterOwnerRemovalAndTargetDeathWithoutIgnoringPhysicalWalls(GameTestHelper h) throws Exception { super.anyRelationCanReacquireAfterOwnerRemovalAndTargetDeathWithoutIgnoringPhysicalWalls(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void disabledContactRedirectionKeepsPiercingAlongCurrentTrajectory(GameTestHelper h) throws Exception { super.disabledContactRedirectionKeepsPiercingAlongCurrentTrajectory(h); }
}
