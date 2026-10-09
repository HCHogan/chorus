package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class HealingRiftGameTest extends com.imdomestic.chorus.test.HealingRiftGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rift_fixed", maxTicks = 310) @Override
    public void fixedRiftHealsAlliesForFifteenSecondsAfterCasterMovesAndSourceDetaches(GameTestHelper h) throws Exception { super.fixedRiftHealsAlliesForFifteenSecondsAfterCasterMovesAndSourceDetaches(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rift_overlap", maxTicks = 315) @Override
    public void pvpOverlappingRiftsKeepOneHealingRateAndSeparateRemovalAndExpiry(GameTestHelper h) throws Exception { super.pvpOverlappingRiftsKeepOneHealingRateAndSeparateRemovalAndExpiry(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rift_missing", maxTicks = 10) @Override
    public void unavailableTeamReferenceEndsThePartialRiftInsteadOfInventingAllies(GameTestHelper h) throws Exception { super.unavailableTeamReferenceEndsThePartialRiftInsteadOfInventingAllies(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rift_anchor", maxTicks = 10) @Override
    public void storedPositionSurvivesRemovedCasterWhenQueryDoesNotNeedLiveAllegiance(GameTestHelper h) throws Exception { super.storedPositionSurvivesRemovedCasterWhenQueryDoesNotNeedLiveAllegiance(h); }
}
