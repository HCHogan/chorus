package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DamageSnapshotTransferGameTest extends com.imdomestic.chorus.test.DamageSnapshotTransferGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:snapshot_native_transfer", maxTicks = 50) @Override
    public void nativeTransferResolvesHighClockSnapshotWithFrozenUseAndCurrentHitModifiers(GameTestHelper h) throws Exception {
        super.nativeTransferResolvesHighClockSnapshotWithFrozenUseAndCurrentHitModifiers(h);
    }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:snapshot_native_restore", maxTicks = 50) @Override
    public void jointArchiveKeepsBuffOwnedCaptureTimelineWithoutReplayingNativeDamage(GameTestHelper h) throws Exception {
        super.jointArchiveKeepsBuffOwnedCaptureTimelineWithoutReplayingNativeDamage(h);
    }
}
