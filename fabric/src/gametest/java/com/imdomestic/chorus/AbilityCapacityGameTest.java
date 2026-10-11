package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AbilityCapacityGameTest extends com.imdomestic.chorus.test.AbilityCapacityGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void physicalEquipmentResizesTheCurrentSkillAndPreservesOtherAccountsAndMeters(GameTestHelper h){super.physicalEquipmentResizesTheCurrentSkillAndPreservesOtherAccountsAndMeters(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ability_capacity_ticks",maxTicks=40) @Override
    public void realRechargeUsesTheExpandedCeilingWithoutChangingItsRateOrProgress(GameTestHelper h){super.realRechargeUsesTheExpandedCeilingWithoutChangingItsRateOrProgress(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ability_capacity_later",maxTicks=25) @Override
    public void aDelayedResizeTargetsTheSelectionAtExecutionAndSkipsEmptyOrFixedSkills(GameTestHelper h){super.aDelayedResizeTargetsTheSelectionAtExecutionAndSkipsEmptyOrFixedSkills(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownNativeObserverPreservesTheEquipmentCapacityAndAppliedHealth(GameTestHelper h){super.unknownNativeObserverPreservesTheEquipmentCapacityAndAppliedHealth(h);}
}
