package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DamageSnapshotGameTest extends com.imdomestic.chorus.test.DamageSnapshotGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:snapshot") @Override
    public void delayedNativeHitRetainsExpiredSourceBonusAndUsesCurrentTargetDefense(GameTestHelper h) throws Exception { super.delayedNativeHitRetainsExpiredSourceBonusAndUsesCurrentTargetDefense(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void onHitBonusAcquiredAfterLaunchParticipatesInTheSameMaxGroup(GameTestHelper h) throws Exception { super.onHitBonusAcquiredAfterLaunchParticipatesInTheSameMaxGroup(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void capturedAttackKeepsOldProfileAcrossRuntimeReplacement(GameTestHelper h) throws Exception { super.capturedAttackKeepsOldProfileAcrossRuntimeReplacement(h); }
}
