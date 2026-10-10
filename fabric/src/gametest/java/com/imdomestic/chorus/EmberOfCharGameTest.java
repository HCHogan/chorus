package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EmberOfCharGameTest extends com.imdomestic.chorus.test.EmberOfCharGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:char_chain",maxTicks=160) @Override
    public void fourRealTargetsKeepIgnitingAcrossFiveWavesAndStopWhenCharIsRemoved(GameTestHelper h) throws Exception { super.fourRealTargetsKeepIgnitingAcrossFiveWavesAndStopWhenCharIsRemoved(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:char_center",maxTicks=60) @Override
    public void delayedExplosionExcludesItsOriginalCenterAfterLockoutAndKeepsFrozenDamage(GameTestHelper h) throws Exception { super.delayedExplosionExcludesItsOriginalCenterAfterLockoutAndKeepsFrozenDamage(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualIgnitionKillCannotStartScorchOnDeadNeighbor(GameTestHelper h) throws Exception { super.actualIgnitionKillCannotStartScorchOnDeadNeighbor(h); }
}
