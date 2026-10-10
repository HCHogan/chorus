package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SolarFragmentStatsGameTest extends com.imdomestic.chorus.test.SolarFragmentStatsGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualArmorComponentsAndCharScalePhysicalPickupWithoutCreatingStatBuffs(GameTestHelper h) throws Exception { super.actualArmorComponentsAndCharScalePhysicalPickupWithoutCreatingStatBuffs(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_energy_change", maxTicks=45) @Override
    public void actualArmorSwapSplitsSelectedMeleeRegenerationAndChangesTheNextGrant(GameTestHelper h) throws Exception { super.actualArmorSwapSplitsSelectedMeleeRegenerationAndChangesTheNextGrant(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void physicalFirespriteUsesCollectorsCurrentCharBonusAndKeepsBasePoints(GameTestHelper h)throws Exception {super.physicalFirespriteUsesCollectorsCurrentCharBonusAndKeepsBasePoints(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:solar_stat_change",maxTicks=45) @Override
    public void realTickEruptionChangesPassiveAndChunkReturnsAtCurrentMeleeStat(GameTestHelper h)throws Exception {super.realTickEruptionChangesPassiveAndChunkReturnsAtCurrentMeleeStat(h);}
}
