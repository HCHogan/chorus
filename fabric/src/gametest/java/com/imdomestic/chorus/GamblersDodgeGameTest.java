package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class GamblersDodgeGameTest extends com.imdomestic.chorus.test.GamblersDodgeGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:gambler_near", maxTicks=30) @Override
    public void actualDodgeReturnsTwoKnivesAndThoseChargesLaunchPhysicalDamage(GameTestHelper h)throws Exception{super.actualDodgeReturnsTwoKnivesAndThoseChargesLaunchPhysicalDamage(h);}
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:gambler_far", maxTicks=30) @Override
    public void actualAlliesAndTargetsOutsideFifteenMetersDoNotDoubleTheReturn(GameTestHelper h)throws Exception{super.actualAlliesAndTargetsOutsideFifteenMetersDoNotDoubleTheReturn(h);}
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:gambler_wall", maxTicks=30) @Override
    public void actualWallClipsDodgeAndAnEnemyAtFifteenMetersStillDoublesReturn(GameTestHelper h)throws Exception{super.actualWallClipsDodgeAndAnEnemyAtFifteenMetersStillDoublesReturn(h);}
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:gambler_ophidia", maxTicks=40) @Override
    public void equippedOphidiaRefreshesOnRealDodgeAndOneCycleRestoresBothKnives(GameTestHelper h)throws Exception{super.equippedOphidiaRefreshesOnRealDodgeAndOneCycleRestoresBothKnives(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void airborneNativeInputRejectsBeforeMovementOrPayment(GameTestHelper h)throws Exception{super.airborneNativeInputRejectsBeforeMovementOrPayment(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownNativeDisplacementRetainsMovementAndCostWithoutEnergyOrReplay(GameTestHelper h)throws Exception{super.unknownNativeDisplacementRetainsMovementAndCostWithoutEnergyOrReplay(h);}
}
