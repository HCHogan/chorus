package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class OphidiaSpatheGameTest extends com.imdomestic.chorus.test.OphidiaSpatheGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void equipmentReplacementAndSolarSelectionRecomputeCapacityWithoutFreeCharges(GameTestHelper h){super.equipmentReplacementAndSolarSelectionRecomputeCapacityWithoutFreeCharges(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ophidia_recharge",maxTicks=40) @Override
    public void recentUseExceptionAndNaturalLinkedRecoveryRunOnRealServerTicks(GameTestHelper h){super.recentUseExceptionAndNaturalLinkedRecoveryRunOnRealServerTicks(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void confirmedDeathsStackKnifeDamageAndProtectedDeathsDoNot(GameTestHelper h){super.confirmedDeathsStackKnifeDamageAndProtectedDeathsDoNot(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ophidia_refresh",maxTicks=160) @Override
    public void gamblerDodgeRefreshesTheExistingBuffWithoutAddingStacks(GameTestHelper h){super.gamblerDodgeRefreshesTheExistingBuffWithoutAddingStacks(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void knifeBonusAddsToExistingMeleeFamilyOnActualDamage(GameTestHelper h){super.knifeBonusAddsToExistingMeleeFamilyOnActualDamage(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownWorldBodyRetainsPaidChargeAndPhysicalHealingWithoutReplay(GameTestHelper h){super.unknownWorldBodyRetainsPaidChargeAndPhysicalHealingWithoutReplay(h);}
}
