package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Fabric registration only; scenarios are shared with NeoForge. */
public class ShieldGameTest extends com.imdomestic.chorus.test.ShieldGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void fifoLayersSpillIntoArmorThenAbsorption(GameTestHelper helper) throws Exception { super.fifoLayersSpillIntoArmorThenAbsorption(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void cancellationImmunityAndCooldownDoNotSpendShield(GameTestHelper helper) throws Exception { super.cancellationImmunityAndCooldownDoNotSpendShield(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void itemBlockingAndImmuneShieldAreDistinct(GameTestHelper helper) throws Exception { super.itemBlockingAndImmuneShieldAreDistinct(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void managedNextActionReadsTheCommittedRemainingShield(GameTestHelper helper) throws Exception { super.managedNextActionReadsTheCommittedRemainingShield(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nestedAfterDamageUsesTheAlreadyConsumedShield(GameTestHelper helper) throws Exception { super.nestedAfterDamageUsesTheAlreadyConsumedShield(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void playerOverrideConsumesShieldBeforeNativeHealth(GameTestHelper helper) throws Exception { super.playerOverrideConsumesShieldBeforeNativeHealth(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void exceptionAfterShieldConsumptionRetainsUnreconciledWrites(GameTestHelper helper) throws Exception { super.exceptionAfterShieldConsumptionRetainsUnreconciledWrites(helper); }
}
