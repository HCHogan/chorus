package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SolarProducerAssemblyGameTest extends com.imdomestic.chorus.test.SolarProducerAssemblyGameTest {
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_assembly_firebolt",maxTicks=100) @Override
    public void fireboltWithoutAClassSelectionKillsThroughScorchAfterItsGrenadeIsDeselected(GameTestHelper h) { super.fireboltWithoutAClassSelectionKillsThroughScorchAfterItsGrenadeIsDeselected(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_assembly_fusion",maxTicks=90) @Override
    public void fusionWithoutAClassSelectionKeepsScorchDamageAfterDeselectionBeforeItsFuse(GameTestHelper h) { super.fusionWithoutAClassSelectionKeepsScorchDamageAfterDeselectionBeforeItsFuse(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_assembly_grenade",maxTicks=70) @Override
    public void solarGrenadeScorchKillsBeforeFieldStartupEvenWhenBothSlotsAreEmpty(GameTestHelper h) { super.solarGrenadeScorchKillsBeforeFieldStartupEvenWhenBothSlotsAreEmpty(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:solar_assembly_phoenix",maxTicks=70) @Override
    public void phoenixDeselectedBeforeLandingStillProducesAConfirmedClassScorchKill(GameTestHelper h) { super.phoenixDeselectedBeforeLandingStillProducesAConfirmedClassScorchKill(h); }
}
