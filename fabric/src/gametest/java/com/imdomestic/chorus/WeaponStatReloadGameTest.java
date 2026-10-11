package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponStatReloadGameTest extends com.imdomestic.chorus.test.WeaponStatReloadGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_reload_timing",maxTicks=85) @Override
    public void actualArmorAndSurplusComposeAfterTheCurveAndExistingReloadKeepsItsDeadline(GameTestHelper h)throws Exception {super.actualArmorAndSurplusComposeAfterTheCurveAndExistingReloadKeepsItsDeadline(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_reload_font",maxTicks=75) @Override
    public void actualOrbFontAndChargeSpendChangeOnlyTheNextReload(GameTestHelper h)throws Exception {super.actualOrbFontAndChargeSpendChangeOnlyTheNextReload(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_reload_insert",maxTicks=95) @Override
    public void everyRealInsertionKeepsItsAcceptedTimingAndTheNextReadsCurrentArmor(GameTestHelper h)throws Exception {super.everyRealInsertionKeepsItsAcceptedTimingAndTheNextReadsCurrentArmor(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_reload_curves",maxTicks=20) @Override
    public void twoPhysicalWeaponsUseTheirOwnPerkAndCurveWithTheSameCharacterMultiplier(GameTestHelper h)throws Exception {super.twoPhysicalWeaponsUseTheirOwnPerkAndCurveWithTheSameCharacterMultiplier(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_reload_unknown",maxTicks=40) @Override
    public void unknownReactionAfterScaledReloadKeepsTransferredAmmoAndAppliedHealing(GameTestHelper h)throws Exception {super.unknownReactionAfterScaledReloadKeepsTransferredAmmoAndAppliedHealing(h);}
}
