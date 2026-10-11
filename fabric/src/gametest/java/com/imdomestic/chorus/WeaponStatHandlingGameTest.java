package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponStatHandlingGameTest extends com.imdomestic.chorus.test.WeaponStatHandlingGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_handling_physical",maxTicks=65) @Override
    public void actualSurplusSwitchKeepsStowTimeAndUsesCurrentArmorForTheOtherWeapon(GameTestHelper h)throws Exception {super.actualSurplusSwitchKeepsStowTimeAndUsesCurrentArmorForTheOtherWeapon(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_handling_floor",maxTicks=50) @Override
    public void amplifiedAndSurplusReachTheFinalStowFloorWhileReadyRetainsBothScalars(GameTestHelper h)throws Exception {super.amplifiedAndSurplusReachTheFinalStowFloorWhileReadyRetainsBothScalars(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_handling_font",maxTicks=55) @Override
    public void realOrbFontAndChargeConsumptionChangeTheFollowingHandlingPhase(GameTestHelper h)throws Exception {super.realOrbFontAndChargeConsumptionChangeTheFollowingHandlingPhase(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_handling_expiry",maxTicks=45) @Override
    public void actualAmplifiedExpiryDoesNotRetimeReadyButTheFollowingStowLosesBothBenefits(GameTestHelper h)throws Exception {super.actualAmplifiedExpiryDoesNotRetimeReadyButTheFollowingStowLosesBothBenefits(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_handling_unknown",maxTicks=30) @Override
    public void unknownReactionAfterScaledReadyKeepsCommittedPhysicalSelectionWithoutReplay(GameTestHelper h)throws Exception {super.unknownReactionAfterScaledReadyKeepsCommittedPhysicalSelectionWithoutReplay(h);}
}
