package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class HarmonicUnflinchingGameTest extends com.imdomestic.chorus.test.HarmonicUnflinchingGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:harmonic_selection", maxTicks=40) @Override
    public void realSuperSelectionChangesTheMixedArmorTierWithoutRestartingNoDistractions(GameTestHelper h) throws Exception { super.realSuperSelectionChangesTheMixedArmorTierWithoutRestartingNoDistractions(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:harmonic_identity", maxTicks=20) @Override
    public void anotherHolderOrMatchingGrenadeDoesNotGiveHarmonicItsElement(GameTestHelper h) throws Exception { super.anotherHolderOrMatchingGrenadeDoesNotGiveHarmonicItsElement(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:harmonic_switch", maxTicks=40) @Override
    public void heldWeaponAndSuperAreMatchedIndependentlyThroughActualFireAndAim(GameTestHelper h) throws Exception { super.heldWeaponAndSuperAreMatchedIndependentlyThroughActualFireAndAim(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:harmonic_unknown", maxTicks=40) @Override
    public void unknownHarmonicFlinchKeepsActualLossAndRotationWithoutRepeatingTheHit(GameTestHelper h) throws Exception { super.unknownHarmonicFlinchKeepsActualLossAndRotationWithoutRepeatingTheHit(h); }
}
