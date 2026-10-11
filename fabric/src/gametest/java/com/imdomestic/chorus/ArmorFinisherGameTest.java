package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ArmorFinisherGameTest extends com.imdomestic.chorus.test.ArmorFinisherGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_finisher_heal", maxTicks=40) @Override
    public void realFinisherConsumesOneOrbChargeAndFullyHealsWithoutAddingAbsorption(GameTestHelper h) throws Exception { super.realFinisherConsumesOneOrbChargeAndFullyHealsWithoutAddingAbsorption(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_finisher_bulwark", maxTicks=40) @Override
    public void sixChargesGiveFourteenSecondSharedShieldThatAbsorbsRealCombatantDamage(GameTestHelper h) throws Exception { super.sixChargesGiveFourteenSecondSharedShieldThatAbsorbsRealCombatantDamage(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_finisher_grenade", maxTicks=40) @Override
    public void explosiveFinisherRewardsCanActuallyPayTheSelectedGrenade(GameTestHelper h) throws Exception { super.explosiveFinisherRewardsCanActuallyPayTheSelectedGrenade(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_finisher_melee", maxTicks=40) @Override
    public void oneTwoFinisherRewardsCanActuallyPayTheSelectedMelee(GameTestHelper h) throws Exception { super.oneTwoFinisherRewardsCanActuallyPayTheSelectedMelee(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_finisher_class", maxTicks=40) @Override
    public void utilityFinisherRewardsCanActuallyPayTheSelectedClassAbility(GameTestHelper h) throws Exception { super.utilityFinisherRewardsCanActuallyPayTheSelectedClassAbility(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_finisher_full", maxTicks=40) @Override
    public void fullHealthKeepsChargesAndLateModSelectionUsesTheCurrentAbilityAccount(GameTestHelper h) throws Exception { super.fullHealthKeepsChargesAndLateModSelectionUsesTheCurrentAbilityAccount(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_finisher_protected", maxTicks=40) @Override
    public void actualDeathProtectionNeverSpendsOrHealsAndInsufficientChargeStillAllowsCompletion(GameTestHelper h) throws Exception { super.actualDeathProtectionNeverSpendsOrHealsAndInsufficientChargeStillAllowsCompletion(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_finisher_modes", maxTicks=40) @Override
    public void mixedEmpoweredAndConsumerSourcesDoNotGenerateAndSpendFromTheSameCompletion(GameTestHelper h) throws Exception { super.mixedEmpoweredAndConsumerSourcesDoNotGenerateAndSpendFromTheSameCompletion(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_finisher_empowered", maxTicks=40) @Override
    public void empoweredDuplicateCopiesGrantOnceAtZeroAndUseStacksOnStacks(GameTestHelper h) throws Exception { super.empoweredDuplicateCopiesGrantOnceAtZeroAndUseStacksOnStacks(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_finisher_unknown", maxTicks=40) @Override
    public void unknownHealingAfterActualCompletionRetainsPaymentAndHealthWithoutAnotherReward(GameTestHelper h) throws Exception { super.unknownHealingAfterActualCompletionRetainsPaymentAndHealthWithoutAnotherReward(h); }
}
