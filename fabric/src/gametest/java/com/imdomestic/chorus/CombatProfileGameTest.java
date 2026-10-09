package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Fabric registration for the shared combat profile scenarios. */
public class CombatProfileGameTest extends com.imdomestic.chorus.test.CombatProfileGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void flatDefenseCannotResurrectCompletelyItemBlockedDamage(GameTestHelper helper) throws Exception { super.flatDefenseCannotResurrectCompletelyItemBlockedDamage(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void managedProfilesFlowThroughShieldArmorAndAbsorption(GameTestHelper helper) throws Exception { super.managedProfilesFlowThroughShieldArmorAndAbsorption(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void ordinaryNativeDamageStillUsesTargetDefenseWithoutAnAttackProfile(GameTestHelper helper) throws Exception { super.ordinaryNativeDamageStillUsesTargetDefenseWithoutAnAttackProfile(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void playerSuperclassDelegationAppliesEachProfileOnce(GameTestHelper helper) throws Exception { super.playerSuperclassDelegationAppliesEachProfileOnce(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void cancellationAndCooldownSkipDefenseAndOutgoingPrecedesCooldown(GameTestHelper helper) throws Exception { super.cancellationAndCooldownSkipDefenseAndOutgoingPrecedesCooldown(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void completeResistanceBlocksWithoutSpendingShield(GameTestHelper helper) throws Exception { super.completeResistanceBlocksWithoutSpendingShield(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nestedDamageRetainsIndependentProfileTracesAndShieldWrites(GameTestHelper helper) throws Exception { super.nestedDamageRetainsIndependentProfileTracesAndShieldWrites(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void explicitProfileWithoutRuntimeFailsBeforeWorldDamage(GameTestHelper helper) { super.explicitProfileWithoutRuntimeFailsBeforeWorldDamage(helper); }
}
