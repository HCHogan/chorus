package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Fabric registration only; scenarios are shared with NeoForge. */
public class DamageGameTest extends com.imdomestic.chorus.test.DamageGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void armorAndAbsorptionUseActualLoss(GameTestHelper helper) { super.armorAndAbsorptionUseActualLoss(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void totemProducesProtectionButNoDeathOrKill(GameTestHelper helper) { super.totemProducesProtectionButNoDeathOrKill(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void bypassedTotemStillConfirmsDeath(GameTestHelper helper) { super.bypassedTotemStillConfirmsDeath(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nonLethalDamagePreservesHealthWithoutConsumingProtection(GameTestHelper helper) { super.nonLethalDamagePreservesHealthWithoutConsumingProtection(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void cancellationAndImmunityRemainDistinct(GameTestHelper helper) { super.cancellationAndImmunityRemainDistinct(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void itemBlockingProducesAHitWithoutDamageTaken(GameTestHelper helper) { super.itemBlockingProducesAHitWithoutDamageTaken(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void reentrantNativeDamageIsNotCountedInTheParentReceipt(GameTestHelper helper) { super.reentrantNativeDamageIsNotCountedInTheParentReceipt(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void sliceAcceptsTheLivingTargetAfterAnActualTotemSave(GameTestHelper helper) throws Exception { super.sliceAcceptsTheLivingTargetAfterAnActualTotemSave(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void serverPlayerOverrideConfirmsDeath(GameTestHelper helper) { super.serverPlayerOverrideConfirmsDeath(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void jsonDamageResumesAndTriggersKillRulesInTheRealWorld(GameTestHelper helper) { super.jsonDamageResumesAndTriggersKillRulesInTheRealWorld(helper); }
}
