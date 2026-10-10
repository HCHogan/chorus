package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SuppressionGameTest extends com.imdomestic.chorus.test.SuppressionGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:suppression_interrupt",maxTicks=24) @Override
    public void actualSuppressionStopsActiveAbilityTicksButAllowsWeaponsAndDetachedEffects(GameTestHelper h){super.actualSuppressionStopsActiveAbilityTicksButAllowsWeaponsAndDetachedEffects(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:suppression_expiry",maxTicks=124) @Override
    public void fiveSecondPvpSuppressionExpiresWithoutResumingTheInterruptedSuper(GameTestHelper h){super.fiveSecondPvpSuppressionExpiresWithoutResumingTheInterruptedSuper(h);}
}
