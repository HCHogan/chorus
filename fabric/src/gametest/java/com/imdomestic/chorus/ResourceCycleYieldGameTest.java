package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ResourceCycleYieldGameTest extends com.imdomestic.chorus.test.ResourceCycleYieldGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void physicalReplacementPreservesProgressAndChangesOnlyTheNextCycleYield(GameTestHelper h) { super.physicalReplacementPreservesProgressAndChangesOnlyTheNextCycleYield(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:cycle_yield_equip", maxTicks=35) @Override
    public void equippingDuringRealRechargeRestoresBothUsesAtTheOriginalBoundary(GameTestHelper h) { super.equippingDuringRealRechargeRestoresBothUsesAtTheOriginalBoundary(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:cycle_yield_remove", maxTicks=55) @Override
    public void removingEquipmentDuringRealRechargeReturnsToOneUsePerCycle(GameTestHelper h) { super.removingEquipmentDuringRealRechargeReturnsToOneUsePerCycle(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownCompletionObserverKeepsBothAccountsAndThePhysicalEquipment(GameTestHelper h) { super.unknownCompletionObserverKeepsBothAccountsAndThePhysicalEquipment(h); }
}
