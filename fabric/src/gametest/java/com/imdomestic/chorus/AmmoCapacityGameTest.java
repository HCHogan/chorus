package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AmmoCapacityGameTest extends com.imdomestic.chorus.test.AmmoCapacityGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void currentCapacityControlsRefillsAndPercentagesBeforeNativeActions(GameTestHelper h) throws Exception { super.currentCapacityControlsRefillsAndPercentagesBeforeNativeActions(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ammo_capacity_delay", maxTicks = 30) @Override
    public void realExpiryPreservesOverflowAndDetachedCapacitySnapshot(GameTestHelper h) throws Exception { super.realExpiryPreservesOverflowAndDetachedCapacitySnapshot(h); }
}
