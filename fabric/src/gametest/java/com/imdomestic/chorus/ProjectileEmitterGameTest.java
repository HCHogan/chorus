package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProjectileEmitterGameTest extends com.imdomestic.chorus.test.ProjectileEmitterGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void constructEmitterIsExcludedWhilePlayerAllianceAndDetachedCreditRemainOriginal(GameTestHelper h) throws Exception { super.constructEmitterIsExcludedWhilePlayerAllianceAndDetachedCreditRemainOriginal(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void missingEmitterPositionReturnsFailureWithoutPretendingAProjectileWasFired(GameTestHelper h) throws Exception { super.missingEmitterPositionReturnsFailureWithoutPretendingAProjectileWasFired(h); }
}
