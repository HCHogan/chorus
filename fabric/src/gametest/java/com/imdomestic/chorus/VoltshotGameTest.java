package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class VoltshotGameTest extends com.imdomestic.chorus.test.VoltshotGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeKillReloadAndHitApplySharedJoltWhileKeepingAnotherWeaponsChargeAndTriggerOwnership(GameTestHelper h) throws Exception { super.nativeKillReloadAndHitApplySharedJoltWhileKeepingAnotherWeaponsChargeAndTriggerOwnership(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void cancellationPreservesReadyButDeniedOrLethalHitsConsumeItAndRealWeaponDeathReopensWindow(GameTestHelper h) throws Exception { super.cancellationPreservesReadyButDeniedOrLethalHitsConsumeItAndRealWeaponDeathReopensWindow(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:voltshot_credit", maxTicks = 120) @Override
    public void actualJoltKillAfterWeaponWindowExpiresCannotRearmVoltshot(GameTestHelper h) throws Exception { super.actualJoltKillAfterWeaponWindowExpiresCannotRearmVoltshot(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:voltshot_expiry", maxTicks = 170) @Override
    public void readyDurationsKeepRunningWhileStowedAndExpireSeparatelyOnRealTicks(GameTestHelper h) throws Exception { super.readyDurationsKeepRunningWhileStowedAndExpireSeparatelyOnRealTicks(h); }
}
