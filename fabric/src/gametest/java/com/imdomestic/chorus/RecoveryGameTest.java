package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class RecoveryGameTest extends com.imdomestic.chorus.test.RecoveryGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void continuousRecoveryIncludesTheFinalFractionOfATick(GameTestHelper helper) throws Exception { super.continuousRecoveryIncludesTheFinalFractionOfATick(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void restorationAndRiftShareAChannelWithoutAddingTheirRates(GameTestHelper helper) throws Exception { super.restorationAndRiftShareAChannelWithoutAddingTheirRates(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void cappedRecoveryReportsOverhealAndNeverStoresItForLater(GameTestHelper helper) throws Exception { super.cappedRecoveryReportsOverhealAndNeverStoresItForLater(helper); }
}
