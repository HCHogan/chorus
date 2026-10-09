package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EffectProgramsGameTest extends com.imdomestic.chorus.test.EffectProgramsGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void dataPackProgramAndAdministrativeBindingsDriveNativeDamage(GameTestHelper helper) throws Exception { super.dataPackProgramAndAdministrativeBindingsDriveNativeDamage(helper); }
    @GameTest(structure = "chorus_gametest:empty", maxTicks = 100) @Override
    public void reloadOverridesNewCatalogueButPinsLiveRuntimeAndRejectsBrokenPack(GameTestHelper helper) throws Exception { super.reloadOverridesNewCatalogueButPinsLiveRuntimeAndRejectsBrokenPack(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void dataPackResourceCostsSurviveDetachAndDoNotResetOnRebind(GameTestHelper helper) throws Exception { super.dataPackResourceCostsSurviveDetachAndDoNotResetOnRebind(helper); }
}
