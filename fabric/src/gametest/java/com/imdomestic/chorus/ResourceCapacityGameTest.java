package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ResourceCapacityGameTest extends com.imdomestic.chorus.test.ResourceCapacityGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void expandingAndShrinkingKeepOneAccountAndUseActualDiscardedEnergyForHealing(GameTestHelper h) { super.expandingAndShrinkingKeepOneAccountAndUseActualDiscardedEnergyForHealing(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_capacity_ticks",maxTicks=30) @Override
    public void realTicksSettleEachCeilingAndDoNotBankTimeSpentAtTheSmallerCapacity(GameTestHelper h) { super.realTicksSettleEachCeilingAndDoNotBankTimeSpentAtTheSmallerCapacity(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownNativeFollowupRetainsClippedEnergyAndActualHealingWithoutReplay(GameTestHelper h) { super.unknownNativeFollowupRetainsClippedEnergyAndActualHealingWithoutReplay(h); }
}
