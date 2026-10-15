package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EffectCheckpointGameTest extends com.imdomestic.chorus.test.EffectCheckpointGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void coordinatedWeaponRestoresItsPaymentAndUsesRemoteResourcesForLaterPhysicalHealing(GameTestHelper h) {
        super.coordinatedWeaponRestoresItsPaymentAndUsesRemoteResourcesForLaterPhysicalHealing(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void coordinatedWeaponLostNativeReceiptRetainsAmmoPaymentAndNeverRepeatsHealing(GameTestHelper h) {
        super.coordinatedWeaponLostNativeReceiptRetainsAmmoPaymentAndNeverRepeatsHealing(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void restoredPausedPeriodicHealingUsesOriginalRemainderAndDoesNotRepeatCompletedHealing(GameTestHelper h) throws Exception {
        super.restoredPausedPeriodicHealingUsesOriginalRemainderAndDoesNotRepeatCompletedHealing(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualHealingWithLostAcknowledgementCannotBeSavedAsSettledOrRepeated(GameTestHelper h) throws Exception {
        super.actualHealingWithLostAcknowledgementCannotBeSavedAsSettledOrRepeated(h);
    }
}
