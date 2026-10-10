package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProcPolicyGameTest extends com.imdomestic.chorus.test.ProcPolicyGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void managedLethalDamageStillHealsThroughUnrelatedRulesButDeniesKeyedKillReward(GameTestHelper h) throws Exception { super.managedLethalDamageStillHealsThroughUnrelatedRulesButDeniesKeyedKillReward(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:proc_projectile", maxTicks = 25) @Override
    public void projectileKeepsItsExclusionAfterLaunchSourceIsRemoved(GameTestHelper h) throws Exception { super.projectileKeepsItsExclusionAfterLaunchSourceIsRemoved(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeAdapterPreservesExplicitPolicyAndDefaultAllowsAllThreeRuleSources(GameTestHelper h) throws Exception { super.nativeAdapterPreservesExplicitPolicyAndDefaultAllowsAllThreeRuleSources(h); }
}
