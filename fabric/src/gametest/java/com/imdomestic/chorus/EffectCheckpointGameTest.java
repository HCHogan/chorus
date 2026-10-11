package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EffectCheckpointGameTest extends com.imdomestic.chorus.test.EffectCheckpointGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void restoredPausedPeriodicHealingUsesOriginalRemainderAndDoesNotRepeatCompletedHealing(GameTestHelper h) throws Exception {
        super.restoredPausedPeriodicHealingUsesOriginalRemainderAndDoesNotRepeatCompletedHealing(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualHealingWithLostAcknowledgementCannotBeSavedAsSettledOrRepeated(GameTestHelper h) throws Exception {
        super.actualHealingWithLostAcknowledgementCannotBeSavedAsSettledOrRepeated(h);
    }
}
