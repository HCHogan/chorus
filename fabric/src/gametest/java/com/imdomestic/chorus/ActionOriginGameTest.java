package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ActionOriginGameTest extends com.imdomestic.chorus.test.ActionOriginGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeHitByAnotherAttackerOwnsChainKillsHealingAndNumericModifiers(GameTestHelper h) throws Exception { super.nativeHitByAnotherAttackerOwnsChainKillsHealingAndNumericModifiers(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:action_origin") @Override
    public void capturedTriggerOriginSurvivesStatusExpiryAndUnbindingBeforeActualDelayedDamage(GameTestHelper h) throws Exception { super.capturedTriggerOriginSurvivesStatusExpiryAndUnbindingBeforeActualDelayedDamage(h); }
}
