package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SpiritArmamentariumGameTest extends com.imdomestic.chorus.test.SpiritArmamentariumGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void physicalClassItemBeforeSelectionAllowsTwoActualGrenadeThrowsAfterCharging(GameTestHelper h) { super.physicalClassItemBeforeSelectionAllowsTwoActualGrenadeThrowsAfterCharging(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void physicalReplacementKeepsEnergyAndUnequippingRetainsOnlyIndependentCapacity(GameTestHelper h) { super.physicalReplacementKeepsEnergyAndUnequippingRetainsOnlyIndependentCapacity(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownCapacityObserverKeepsActualEquipmentAndHealingWithoutReplay(GameTestHelper h) { super.unknownCapacityObserverKeepsActualEquipmentAndHealingWithoutReplay(h); }
}
