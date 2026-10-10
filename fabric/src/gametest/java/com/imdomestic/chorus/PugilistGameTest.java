package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PugilistGameTest extends com.imdomestic.chorus.test.PugilistGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void equippedWeaponKillsCreditCurrentMeleeSelectionEvenWhenItChangesInFlight(GameTestHelper h) throws Exception { super.equippedWeaponKillsCreditCurrentMeleeSelectionEvenWhenItChangesInFlight(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void uncreditedKillsAndAbsentMeleeSelectionDoNotCreateEnergyOrFailTheRuntime(GameTestHelper h) throws Exception { super.uncreditedKillsAndAbsentMeleeSelectionDoNotCreateEnergyOrFailTheRuntime(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:pugilist_handling", maxTicks = 95) @Override
    public void actualMeleeDamageRefreshesThreeSecondHandlingAndImmuneContactDoesNot(GameTestHelper h) throws Exception { super.actualMeleeDamageRefreshesThreeSecondHandlingAndImmuneContactDoesNot(h); }
}
