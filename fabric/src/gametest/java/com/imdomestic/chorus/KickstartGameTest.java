package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class KickstartGameTest extends com.imdomestic.chorus.test.KickstartGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:kickstart_grenade") @Override
    public void grenadePaidUseConsumesOneActualOrbAndGrantsTheLowestTableTier(GameTestHelper h) throws Exception { super.grenadePaidUseConsumesOneActualOrbAndGrantsTheLowestTableTier(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:kickstart_melee") @Override
    public void meleeTwoCopiesAndFourActualOrbsSelectTheCombinedTierOnce(GameTestHelper h) throws Exception { super.meleeTwoCopiesAndFourActualOrbsSelectTheCombinedTierOnce(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:kickstart_class") @Override
    public void utilityThreeCopiesAndSixActualOrbsSelectTheMaximumTierOnce(GameTestHelper h) throws Exception { super.utilityThreeCopiesAndSixActualOrbsSelectTheMaximumTierOnce(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:kickstart_multicharge") @Override
    public void meleeTriggersWithOneWholeChargeRemainingAndTheNextIndependentUseCanTriggerAgain(GameTestHelper h) throws Exception { super.meleeTriggersWithOneWholeChargeRemainingAndTheNextIndependentUseCanTriggerAgain(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:kickstart_excluded") @Override
    public void freePartialRejectedDeferredAndOrdinaryUnpoweredAttacksDoNotSpendCharges(GameTestHelper h) throws Exception { super.freePartialRejectedDeferredAndOrdinaryUnpoweredAttacksDoNotSpendCharges(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:kickstart_shield_throw") @Override
    public void actualShieldProjectileConsumesChargesAndUsesItsOwnRecipientScalarWithoutSyntheticSkillBody(GameTestHelper h) throws Exception { super.actualShieldProjectileConsumesChargesAndUsesItsOwnRecipientScalarWithoutSyntheticSkillBody(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:kickstart_fonts") @Override
    public void actualConsumptionRemovesPassiveFontAndAppliesAdditionalEnergyGainOnlyOnce(GameTestHelper h) throws Exception { super.actualConsumptionRemovesPassiveFontAndAppliesAdditionalEnergyGainOnlyOnce(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:kickstart_identity") @Override
    public void lateCopiesAndReplayedAcceptedIdentityCannotSpendNewlyCollectedOrbs(GameTestHelper h) throws Exception { super.lateCopiesAndReplayedAcceptedIdentityCannotSpendNewlyCollectedOrbs(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:kickstart_unknown") @Override
    public void unknownSkillHealingRetainsCostArmorPaymentAndRefundWithoutRepeatingWorldWork(GameTestHelper h) throws Exception { super.unknownSkillHealingRetainsCostArmorPaymentAndRefundWithoutRepeatingWorldWork(h); }
}
