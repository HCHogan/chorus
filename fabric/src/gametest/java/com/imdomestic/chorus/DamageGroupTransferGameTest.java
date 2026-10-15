package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DamageGroupTransferGameTest extends com.imdomestic.chorus.test.DamageGroupTransferGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:damage_group_native_transfer", maxTicks = 50) @Override
    public void nativeTransferKeepsSourceOwnedGroupAtIssuerThroughConsumptionAndClose(GameTestHelper h) throws Exception {
        super.nativeTransferKeepsSourceOwnedGroupAtIssuerThroughConsumptionAndClose(h);
    }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:damage_group_native_restore", maxTicks = 50) @Override
    public void jointArchiveRestoresBuffOwnedGroupWithoutReplayingFirstNativeConsumption(GameTestHelper h) throws Exception {
        super.jointArchiveRestoresBuffOwnedGroupWithoutReplayingFirstNativeConsumption(h);
    }
}
