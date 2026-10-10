package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class BreakoutGameTest extends com.imdomestic.chorus.test.BreakoutGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:breakout_air",maxTicks=35) @Override
    public void airborneClassInputStartsOnceThenPaysHealthAndRestoresTheOriginalClassAbility(GameTestHelper h){super.airborneClassInputStartsOnceThenPaysHealthAndRestoresTheOriginalClassAbility(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:breakout_generation",maxTicks=45) @Override
    public void oldBreakoutCannotChargeOrClearANewFreezeGeneration(GameTestHelper h){super.oldBreakoutCannotChargeOrClearANewFreezeGeneration(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:breakout_low_health",maxTicks=35) @Override
    public void lowHealthAndDetachedCalibrationStillFinishTheAcceptedBreakout(GameTestHelper h){super.lowHealthAndDetachedCalibrationStillFinishTheAcceptedBreakout(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:breakout_fault",maxTicks=35) @Override
    public void paymentReceiptFailureKeepsHealthWriteButDoesNotThawOrReplay(GameTestHelper h){super.paymentReceiptFailureKeepsHealthWriteButDoesNotThawOrReplay(h);}
}
