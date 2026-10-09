package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class LayerProfileGameTest extends com.imdomestic.chorus.test.LayerProfileGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void perLayerAttackScalingSpillsUnscaledBudgetIntoNativeArmorAndAbsorption(GameTestHelper h) throws Exception { super.perLayerAttackScalingSpillsUnscaledBudgetIntoNativeArmorAndAbsorption(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:layer_profile_snapshot", maxTicks = 10) @Override
    public void detachedJsonAttackRetainsEnhancedBonusAndChoosesTheNewLayerAtImpact(GameTestHelper h) throws Exception { super.detachedJsonAttackRetainsEnhancedBonusAndChoosesTheNewLayerAtImpact(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void rejectedHitsNeverConsumeLayersAndCooldownUsesUnscaledDamageBudget(GameTestHelper h) throws Exception { super.rejectedHitsNeverConsumeLayersAndCooldownUsesUnscaledDamageBudget(h); }
}
