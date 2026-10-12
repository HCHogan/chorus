package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class RicochetRoundsGameTest extends com.imdomestic.chorus.test.RicochetRoundsGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_perk_damage",maxTicks=100) @Override
    public void bothVariantsChangeEquippedStatsAndActualReflectedFalloff(GameTestHelper h)throws Exception{super.bothVariantsChangeEquippedStatsAndActualReflectedFalloff(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_perk_switch",maxTicks=100) @Override
    public void changingTheDrawnWeaponEnablesReflectionWithoutBorrowingItsPerk(GameTestHelper h)throws Exception{super.changingTheDrawnWeaponEnablesReflectionWithoutBorrowingItsPerk(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_perk_layers",maxTicks=100) @Override
    public void reflectionAndPenetrationComposeWithQualifiedShieldScaling(GameTestHelper h)throws Exception{super.reflectionAndPenetrationComposeWithQualifiedShieldScaling(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_perk_shield",maxTicks=100) @Override
    public void ricochetAloneDoesNotBorrowArmorPiercingsShieldBonus(GameTestHelper h)throws Exception{super.ricochetAloneDoesNotBorrowArmorPiercingsShieldBonus(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:ricochet_perk_unknown",maxTicks=100) @Override
    public void unknownDamageRetainsPaymentAndDoesNotContinueTheReflectedPath(GameTestHelper h)throws Exception{super.unknownDamageRetainsPaymentAndDoesNotContinueTheReflectedPath(h);}
}
