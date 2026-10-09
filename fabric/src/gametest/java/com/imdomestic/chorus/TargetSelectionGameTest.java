package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class TargetSelectionGameTest extends com.imdomestic.chorus.test.TargetSelectionGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void exclusionsRunBeforeNearestLimitAndDistanceTiesUseStableIdentity(GameTestHelper h) { super.exclusionsRunBeforeNearestLimitAndDistanceTiesUseStableIdentity(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void radialCurveChangesActualDamageUsingCapturedDistancesAfterTargetsMove(GameTestHelper h) throws Exception { super.radialCurveChangesActualDamageUsingCapturedDistancesAfterTargetsMove(h); }
}
