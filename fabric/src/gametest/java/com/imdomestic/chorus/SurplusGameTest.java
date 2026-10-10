package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SurplusGameTest extends com.imdomestic.chorus.test.SurplusGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void extraChargesCountSeparatelyAndSpendingUpdatesOnlyThePerkWeapon(GameTestHelper h) throws Exception { super.extraChargesCountSeparatelyAndSpendingUpdatesOnlyThePerkWeapon(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualWellspringKillCompletesAChargeAndImmediatelyRaisesSurplusReloadSpeed(GameTestHelper h) throws Exception { super.actualWellspringKillCompletesAChargeAndImmediatelyRaisesSurplusReloadSpeed(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:surplus_reload", maxTicks = 85) @Override
    public void acceptedReloadDeadlineStaysFixedWhileTheNextReloadUsesNewCharges(GameTestHelper h) throws Exception { super.acceptedReloadDeadlineStaysFixedWhileTheNextReloadUsesNewCharges(h); }
}
