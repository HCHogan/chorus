package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class KineticTremorsGameTest extends com.imdomestic.chorus.test.KineticTremorsGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:tremors_pve", maxTicks = 65) @Override
    public void realHitsTriggerThreeFrozenRankWavesAfterStowDetachAndOriginRemoval(GameTestHelper h) throws Exception { super.realHitsTriggerThreeFrozenRankWavesAfterStowDetachAndOriginRemoval(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:tremors_pvp", maxTicks = 110) @Override
    public void enhancedAndNormalWeaponsKeepSeparateCountersAndPvpCooldownReopensAfterFinalWave(GameTestHelper h) throws Exception { super.enhancedAndNormalWeaponsKeepSeparateCountersAndPvpCooldownReopensAfterFinalWave(h); }
}
