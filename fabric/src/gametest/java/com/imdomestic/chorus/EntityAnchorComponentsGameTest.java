package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EntityAnchorComponentsGameTest extends com.imdomestic.chorus.test.EntityAnchorComponentsGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:anchor_component_landing", maxTicks = 60) @Override
    public void pendingStateMatchesRealLandingAndDelayedWorkRetainsItsHistoricalCenter(GameTestHelper h) { super.pendingStateMatchesRealLandingAndDelayedWorkRetainsItsHistoricalCenter(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:anchor_component_replacement", maxTicks = 60) @Override
    public void replacementWithTheSameUuidCannotCompleteAnOldCastButCanBeArmedIndependently(GameTestHelper h) { super.replacementWithTheSameUuidCannotCompleteAnOldCastButCanBeArmedIndependently(h); }
}
