package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ShieldRecoveryGameTest extends com.imdomestic.chorus.test.ShieldRecoveryGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:shield_recovery_cap", maxTicks = 10) @Override
    public void nativeDamageRestartsLayerDelayAndRecoveryUsesTheResidualInterval(GameTestHelper h) throws Exception { super.nativeDamageRestartsLayerDelayAndRecoveryUsesTheResidualInterval(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:shield_recovery_expiry", maxTicks = 10) @Override
    public void finalSubTickRecoveryIsRetainedInExpirySnapshotAndNeverHealsNativeHealth(GameTestHelper h) throws Exception { super.finalSubTickRecoveryIsRetainedInExpirySnapshotAndNeverHealsNativeHealth(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:eternal_warrior_recovery", maxTicks = 190) @Override
    public void eternalWarriorWaitsFiveSecondsThenRecoversAndCannotRegrowAfterBreaking(GameTestHelper h) throws Exception { super.eternalWarriorWaitsFiveSecondsThenRecoversAndCannotRegrowAfterBreaking(h); }
}
