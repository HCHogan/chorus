package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EmberOfSearingGameTest extends com.imdomestic.chorus.test.EmberOfSearingGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void nativeAndPhysicalKillsOfAnotherSourcesScorchRestoreMeleeWhileFirespriteCooldownIsIndependent(GameTestHelper h) throws Exception {super.nativeAndPhysicalKillsOfAnotherSourcesScorchRestoreMeleeWhileFirespriteCooldownIsIndependent(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unmarkedKillsDoNothingAndUnknownCombatantTierIsExplicitWithoutGuessingRank(GameTestHelper h) throws Exception {super.unmarkedKillsDoNothingAndUnknownCombatantTierIsExplicitWithoutGuessingRank(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:searing_dot",maxTicks=40) @Override
    public void actualScorchTickDeathUsesItsOriginalKillerAndRetainsObservedScorchForSearing(GameTestHelper h) throws Exception {super.actualScorchTickDeathUsesItsOriginalKillerAndRetainsObservedScorchForSearing(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void corpseCleanupPreservesSearingTierButMissingPositionCannotCreateAPickup(GameTestHelper h)throws Exception{super.corpseCleanupPreservesSearingTierButMissingPositionCannotCreateAPickup(h);}
}
