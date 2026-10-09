package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PrecisionShieldGameTest extends com.imdomestic.chorus.test.PrecisionShieldGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void precisionSuppressionReplaysFlatDamageAndSpillsThroughAnotherShieldArmorAndAbsorption(GameTestHelper h) throws Exception { super.precisionSuppressionReplaysFlatDamageAndSpillsThroughAnotherShieldArmorAndAbsorption(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:precision_snapshot", maxTicks = 10) @Override
    public void delayedAttackKeepsFactorDefinitionsAndSourceValuesWhileImpactAndShieldArriveLater(GameTestHelper h) throws Exception { super.delayedAttackKeepsFactorDefinitionsAndSourceValuesWhileImpactAndShieldArriveLater(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void rejectedPrecisionHitsKeepCapacityAndNativeCooldownAdmitsOnlyItsIncrement(GameTestHelper h) throws Exception { super.rejectedPrecisionHitsKeepCapacityAndNativeCooldownAdmitsOnlyItsIncrement(h); }
}
