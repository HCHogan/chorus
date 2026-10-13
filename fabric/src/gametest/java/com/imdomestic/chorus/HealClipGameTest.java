package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class HealClipGameTest extends com.imdomestic.chorus.test.HealClipGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heal_clip_chain", maxTicks = 100) @Override
    public void physicalShotKillReloadAndAlliedCureComposeWithBenevolenceAtTheRadiusBoundary(GameTestHelper h) { super.physicalShotKillReloadAndAlliedCureComposeWithBenevolenceAtTheRadiusBoundary(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heal_clip_refill", maxTicks = 100) @Override
    public void ordinaryRefillAfterAnActualKillCannotImpersonateACompletedReload(GameTestHelper h) { super.ordinaryRefillAfterAnActualKillCannotImpersonateACompletedReload(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heal_clip_instant", maxTicks = 100) @Override
    public void explicitlyQualifiedZeroTransferReloadUsesTheSameCureAndWeaponWindow(GameTestHelper h) { super.explicitlyQualifiedZeroTransferReloadUsesTheSameCureAndWeaponWindow(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heal_clip_unknown", maxTicks = 100) @Override
    public void unknownNativeCureKeepsCommittedAmmoAndHealthWithoutReplayingOrInventingCredit(GameTestHelper h) { super.unknownNativeCureKeepsCommittedAmmoAndHealthWithoutReplayingOrInventingCredit(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:heal_clip_insert", maxTicks = 100) @Override
    public void incrementalReloadConsumesOneWindowAndLaterInsertionsDoNotRepeatThatKill(GameTestHelper h) { super.incrementalReloadConsumesOneWindowAndLaterInsertionsDoNotRepeatThatKill(h); }
}
