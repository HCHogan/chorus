package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Fabric registration for shared healing scenarios. */
public class HealingGameTest extends com.imdomestic.chorus.test.HealingGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void healthCapAndFullHealthPreserveActualAndOverheal(GameTestHelper helper) { super.healthCapAndFullHealthPreserveActualAndOverheal(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void jsonDamageResultHealsOnlyActualHealthLoss(GameTestHelper helper) throws Exception { super.jsonDamageResultHealsOnlyActualHealthLoss(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void missingRemovedAndDeadTargetsAreNotHealed(GameTestHelper helper) { super.missingRemovedAndDeadTargetsAreNotHealed(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void playerHealingUsesMaxHealthAndLeavesAbsorptionUntouched(GameTestHelper helper) { super.playerHealingUsesMaxHealthAndLeavesAbsorptionUntouched(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void duplicateWorldOperationReturnsReceiptWithoutHealingTwice(GameTestHelper helper) { super.duplicateWorldOperationReturnsReceiptWithoutHealingTwice(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void logicalPeriodicSignalsCanExecuteRealHealing(GameTestHelper helper) throws Exception { super.logicalPeriodicSignalsCanExecuteRealHealing(helper); }
}
