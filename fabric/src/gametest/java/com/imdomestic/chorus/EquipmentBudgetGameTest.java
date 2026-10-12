package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EquipmentBudgetGameTest extends com.imdomestic.chorus.test.EquipmentBudgetGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void overBudgetClientReplacementPreservesBothOwnedStacksAndTheActiveEffects(GameTestHelper h) throws Exception { super.overBudgetClientReplacementPreservesBothOwnedStacksAndTheActiveEffects(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void perItemCapacitySurvivesPhysicalSaveAndRejectsUnderfundedRolls(GameTestHelper h) throws Exception { super.perItemCapacitySurvivesPhysicalSaveAndRejectsUnderfundedRolls(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void aStricterReloadDeactivatesInvalidEquipmentWithoutPreventingRetrieval(GameTestHelper h) throws Exception { super.aStricterReloadDeactivatesInvalidEquipmentWithoutPreventingRetrieval(h); }
}
