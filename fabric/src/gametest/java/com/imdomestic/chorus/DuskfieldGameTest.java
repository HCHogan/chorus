package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DuskfieldGameTest extends com.imdomestic.chorus.test.DuskfieldGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:duskfield_members",maxTicks=45) @Override
    public void nativeDotBypassesHitCooldownAndFixedFieldFindsLateEntrantsWithoutFollowingOwner(GameTestHelper h){super.nativeDotBypassesHitCooldownAndFixedFieldFindsLateEntrantsWithoutFollowingOwner(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:duskfield_durance",maxTicks=210) @Override
    public void unequippedGrenadeKeepsNineSecondFieldButLaterSlowUsesCurrentDuranceAndFreezes(GameTestHelper h){super.unequippedGrenadeKeepsNineSecondFieldButLaterSlowUsesCurrentDuranceAndFreezes(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:duskfield_guardian",maxTicks=135) @Override
    public void actualGuardianUsesFiveStacksAndPointThreeSecondPulsesUntilFreeze(GameTestHelper h){super.actualGuardianUsesFiveStacksAndPointThreeSecondPulsesUntilFreeze(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:duskfield_independent",maxTicks=170) @Override
    public void twoCastersKeepIndependentAnchorsAndSevenVersusNineSecondFieldLifetimes(GameTestHelper h){super.twoCastersKeepIndependentAnchorsAndSevenVersusNineSecondFieldLifetimes(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:duskfield_fault",maxTicks=45) @Override
    public void unknownNativeTickKeepsCommittedDamageAndChargeWithoutFurtherPulses(GameTestHelper h){super.unknownNativeTickKeepsCommittedDamageAndChargeWithoutFurtherPulses(h);}
}
