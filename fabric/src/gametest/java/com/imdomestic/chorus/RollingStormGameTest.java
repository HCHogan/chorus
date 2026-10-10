package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class RollingStormGameTest extends com.imdomestic.chorus.test.RollingStormGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rolling_cycle", maxTicks = 130) @Override
    public void actualWeaponKillsFillChargeAndOrdinaryMeleeDischargeKillCannotRegrantThePerk(GameTestHelper h) throws Exception { super.actualWeaponKillsFillChargeAndOrdinaryMeleeDischargeKillCannotRegrantThePerk(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rolling_stowed", maxTicks = 25) @Override
    public void enhancedAmplifiedKillAfterStowGrantsThreeStacksToTheOwner(GameTestHelper h) throws Exception { super.enhancedAmplifiedKillAfterStowGrantsThreeStacksToTheOwner(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rolling_credit", maxTicks = 25) @Override
    public void actualKillWithWeaponOriginButNoWeaponKillCreditCannotBootstrapCharge(GameTestHelper h) throws Exception { super.actualKillWithWeaponOriginButNoWeaponKillCreditCannotBootstrapCharge(h); }
}
