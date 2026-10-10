package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WellspringGameTest extends com.imdomestic.chorus.test.WellspringGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualWeaponKillsSplitNormalAndEnhancedEnergyAcrossThreeRecipients(GameTestHelper h) throws Exception { super.actualWeaponKillsSplitNormalAndEnhancedEnergyAcrossThreeRecipients(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:wellspring_split", maxTicks = 40) @Override
    public void serverProjectileTickKeepsTheOriginalDenominatorWhenFirstPoolFills(GameTestHelper h) throws Exception { super.serverProjectileTickKeepsTheOriginalDenominatorWhenFirstPoolFills(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void extraChargePolicyAndInFlightSelectionUseCurrentBaseAccounts(GameTestHelper h) throws Exception { super.extraChargePolicyAndInFlightSelectionUseCurrentBaseAccounts(h); }
}
