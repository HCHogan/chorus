package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EmberOfEruptionGameTest extends com.imdomestic.chorus.test.EmberOfEruptionGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void realEightAndTenMeterBoundariesControlDamageAndCharSpreadUsingOriginalOwner(GameTestHelper h)throws Exception {
        super.realEightAndTenMeterBoundariesControlDamageAndCharSpreadUsingOriginalOwner(h);
    }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:eruption_delayed",maxTicks=40) @Override
    public void delayedBlastKeepsCapturedRadiusAfterUnequippingAndQueriesCurrentMembers(GameTestHelper h)throws Exception {
        super.delayedBlastKeepsCapturedRadiusAfterUnequippingAndQueriesCurrentMembers(h);
    }
}
