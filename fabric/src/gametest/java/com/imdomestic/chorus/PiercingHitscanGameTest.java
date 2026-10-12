package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PiercingHitscanGameTest extends com.imdomestic.chorus.test.PiercingHitscanGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:piercing_ray_two",maxTicks=100) @Override
    public void oneBulletHitsTwoEnemiesAndCountsOneMemberAcrossBoth(GameTestHelper h)throws Exception{super.oneBulletHitsTwoEnemiesAndCountsOneMemberAcrossBoth(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:piercing_ray_perks",maxTicks=40) @Override
    public void confirmedPrecisionContactsDriveBothAmmoPerksWithoutDuplicatingFireCost(GameTestHelper h)throws Exception{super.confirmedPrecisionContactsDriveBothAmmoPerksWithoutDuplicatingFireCost(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:piercing_ray_wall",maxTicks=100) @Override
    public void piercingStopsAtTerrainAndKeepsTheEarlierEntityHit(GameTestHelper h)throws Exception{super.piercingStopsAtTerrainAndKeepsTheEarlierEntityHit(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:piercing_ray_geometry",maxTicks=100) @Override
    public void laterTargetMotionDoesNotRewriteTheAlreadyObservedInstantaneousPath(GameTestHelper h)throws Exception{super.laterTargetMotionDoesNotRewriteTheAlreadyObservedInstantaneousPath(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:piercing_ray_zero",maxTicks=100) @Override
    public void explicitZeroRetainsSingleContactAndLargeBudgetEndsAtTheFiniteScene(GameTestHelper h)throws Exception{super.explicitZeroRetainsSingleContactAndLargeBudgetEndsAtTheFiniteScene(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:piercing_ray_unknown",maxTicks=100) @Override
    public void unknownFirstDamageKeepsOnePaymentAndNeverDamagesTheCapturedSecondTarget(GameTestHelper h)throws Exception{super.unknownFirstDamageKeepsOnePaymentAndNeverDamagesTheCapturedSecondTarget(h);}
}
