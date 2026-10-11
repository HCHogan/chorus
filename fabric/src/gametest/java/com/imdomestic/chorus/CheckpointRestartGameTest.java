package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class CheckpointRestartGameTest extends com.imdomestic.chorus.test.CheckpointRestartGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:checkpoint_restart", maxTicks = 400) @Override
    public void checkpointRestartUsesNativeFilesAndAutomaticLifecycle(GameTestHelper h) throws Exception { super.checkpointRestartUsesNativeFilesAndAutomaticLifecycle(h); }
}
