package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class RicochetHitscanGameTest extends com.imdomestic.chorus.test.RicochetHitscanGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_damage",maxTicks=100) @Override
    public void obliqueWallReflectionUsesCumulativeTravelForTwoActualDamageContacts(GameTestHelper h)throws Exception{super.obliqueWallReflectionUsesCumulativeTravelForTwoActualDamageContacts(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_zero",maxTicks=100) @Override
    public void zeroBouncesLeaveTheRearTargetUntouched(GameTestHelper h)throws Exception{super.zeroBouncesLeaveTheRearTargetUntouched(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_origin",maxTicks=100) @Override
    public void originCollisionIsExcludedInitiallyAndExplicitlyEnabledAfterReflection(GameTestHelper h)throws Exception{super.originCollisionIsExcludedInitiallyAndExplicitlyEnabledAfterReflection(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_budget",maxTicks=100) @Override
    public void repeatedWallsSpendOneSharedRangeBudgetAndDoNotRehitAnEarlierEntity(GameTestHelper h)throws Exception{super.repeatedWallsSpendOneSharedRangeBudgetAndDoNotRehitAnEarlierEntity(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_geometry",maxTicks=100) @Override
    public void capturedReflectionSurvivesWallRemovalAndTargetMotionDuringEarlierActions(GameTestHelper h)throws Exception{super.capturedReflectionSurvivesWallRemovalAndTargetMotionDuringEarlierActions(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_unknown",maxTicks=100) @Override
    public void unknownBounceActionKeepsThePaymentAndStopsBeforeAnyCapturedTargetDamage(GameTestHelper h)throws Exception{super.unknownBounceActionKeepsThePaymentAndStopsBeforeAnyCapturedTargetDamage(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_embedded",maxTicks=100) @Override
    public void embeddedOriginStopsWhileAnExactOutwardFaceDoesNotCreateAPhantomSecondBounce(GameTestHelper h)throws Exception{super.embeddedOriginStopsWhileAnExactOutwardFaceDoesNotCreateAPhantomSecondBounce(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_unloaded",maxTicks=100) @Override
    public void reflectedUnknownBoundaryIsNotAnotherWallAndNeverLoadsTheMissingChunk(GameTestHelper h)throws Exception{super.reflectedUnknownBoundaryIsNotAnotherWallAndNeverLoadsTheMissingChunk(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_revisit",maxTicks=100) @Override
    public void configuredReturnThroughTheSameTargetDamagesTwiceWithoutDuplicatingPelletCredit(GameTestHelper h)throws Exception{super.configuredReturnThroughTheSameTargetDamagesTwiceWithoutDuplicatingPelletCredit(h);}
}
