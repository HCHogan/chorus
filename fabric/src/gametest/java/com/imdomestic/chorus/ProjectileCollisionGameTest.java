package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProjectileCollisionGameTest extends com.imdomestic.chorus.test.ProjectileCollisionGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void wallAndEntityContactsShareOneContinuationBudgetWithoutSkippingFinalDamage(GameTestHelper h)throws Exception{super.wallAndEntityContactsShareOneContinuationBudgetWithoutSkippingFinalDamage(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void zeroSharedBudgetStopsOnFirstEntityOrWallAndNeverReflectsAtAnExhaustedWall(GameTestHelper h)throws Exception{super.zeroSharedBudgetStopsOnFirstEntityOrWallAndNeverReflectsAtAnExhaustedWall(h);}
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void exactEndpointAndNearFaceContactsBounceButEmbeddedLaunchTerminates(GameTestHelper h) throws Exception { super.exactEndpointAndNearFaceContactsBounceButEmbeddedLaunchTerminates(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void finitePiercingHitsTwoOrderedEnemiesInOneSweepAndStopsBeforeTheThird(GameTestHelper h) throws Exception { super.finitePiercingHitsTwoOrderedEnemiesInOneSweepAndStopsBeforeTheThird(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:projectile_reentry", maxTicks = 60) @Override
    public void bouncingProjectileCanReenterEachEnemyTwiceWithCapturedDamageAndLiveBounceFalloff(GameTestHelper h) throws Exception { super.bouncingProjectileCanReenterEachEnemyTwiceWithCapturedDamageAndLiveBounceFalloff(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void twoSurfaceReflectionsUseRemainingTravelInTheSameTickAndExhaustOnlyTheConfiguredBudget(GameTestHelper h) throws Exception { super.twoSurfaceReflectionsUseRemainingTravelInTheSameTickAndExhaustOnlyTheConfiguredBudget(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void anEnemyContainingTheProjectileCannotBeHitRepeatedlyUntilTheProjectileExits(GameTestHelper h) throws Exception { super.anEnemyContainingTheProjectileCannotBeHitRepeatedlyUntilTheProjectileExits(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeCancellationStillConsumesContactBudgetWhileLaterPiercedEnemyCanTakeDamage(GameTestHelper h) throws Exception { super.nativeCancellationStillConsumesContactBudgetWhileLaterPiercedEnemyCanTakeDamage(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void failureOnNonterminalContactConsumesFlightAndDoesNotProcessRemainingSweep(GameTestHelper h) throws Exception { super.failureOnNonterminalContactConsumesFlightAndDoesNotProcessRemainingSweep(h); }
}
