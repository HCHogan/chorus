package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ReactionBindingGameTest extends com.imdomestic.chorus.test.ReactionBindingGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:reaction_unbound", maxTicks = 25) @Override
    public void physicalProjectileKeepsOldHitAndKillRulesAfterSourceRemoval(GameTestHelper h) throws Exception { super.physicalProjectileKeepsOldHitAndKillRulesAfterSourceRemoval(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:reaction_replace", maxTicks = 25) @Override
    public void replacementRunsCurrentRuleAndOriginalEnhancedRuleExactlyOnce(GameTestHelper h) throws Exception { super.replacementRunsCurrentRuleAndOriginalEnhancedRuleExactlyOnce(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeDamageAdapterCapturesImmediateOriginRulesWithoutHostPreprocessing(GameTestHelper h) throws Exception { super.nativeDamageAdapterCapturesImmediateOriginRulesWithoutHostPreprocessing(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:reaction_unknown", maxTicks = 25) @Override
    public void unknownCapturedReactionReceiptPreservesActualHealWithoutReplay(GameTestHelper h) throws Exception { super.unknownCapturedReactionReceiptPreservesActualHealWithoutReplay(h); }
}
