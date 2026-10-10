package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FreezeDamageGameTest extends com.imdomestic.chorus.test.FreezeDamageGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_damage") @Override
    public void actualClassifiedWeaponAndAbilityDamageUsesFrozenTargetFactorsAndClearsImmediately(GameTestHelper h){super.actualClassifiedWeaponAndAbilityDamageUsesFrozenTargetFactorsAndClearsImmediately(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_scaled_threshold") @Override
    public void scaledRealMeleeLossTriggersShatterWhileBossReceivesNoMinorMeleeBonus(GameTestHelper h){super.scaledRealMeleeLossTriggersShatterWhileBossReceivesNoMinorMeleeBonus(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_damage_snapshot") @Override
    public void capturedDamageUsesLiveFreezeAtImpactAndPreservesNativeCredit(GameTestHelper h){super.capturedDamageUsesLiveFreezeAtImpactAndPreservesNativeCredit(h);}
}
