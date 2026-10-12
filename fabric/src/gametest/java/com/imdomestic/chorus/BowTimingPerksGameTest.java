package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class BowTimingPerksGameTest extends com.imdomestic.chorus.test.BowTimingPerksGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:bow_perks_normal",maxTicks=40) @Override
    public void normalPrecisionKillsShortenTheNextPhysicalDrawAndExtendWarmup(GameTestHelper h)throws Exception {super.normalPrecisionKillsShortenTheNextPhysicalDrawAndExtendWarmup(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:bow_perks_enhanced",maxTicks=40) @Override
    public void enhancedPrecisionAndKillTimersUseFourAndFiveSeconds(GameTestHelper h)throws Exception {super.enhancedPrecisionAndKillTimersUseFourAndFiveSeconds(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:bow_perks_expiry",maxTicks=110) @Override
    public void nonlethalPrecisionStartsTempoAndItsExpiryDoesNotChangeAnAcceptedDraw(GameTestHelper h)throws Exception {super.nonlethalPrecisionStartsTempoAndItsExpiryDoesNotChangeAnAcceptedDraw(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:bow_perks_stow",maxTicks=40) @Override
    public void actualStowRemovesBothAndTheAlreadyFlyingPrecisionKillCannotRegrant(GameTestHelper h)throws Exception {super.actualStowRemovesBothAndTheAlreadyFlyingPrecisionKillCannotRegrant(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:bow_perks_delayed",maxTicks=35) @Override
    public void delayedBodyExplosionKillStartsWarmupWithoutPrecisionTempo(GameTestHelper h)throws Exception {super.delayedBodyExplosionKillStartsWarmupWithoutPrecisionTempo(h);}
}
