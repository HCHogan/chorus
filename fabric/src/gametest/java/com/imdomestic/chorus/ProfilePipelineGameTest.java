package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProfilePipelineGameTest extends com.imdomestic.chorus.test.ProfilePipelineGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:pipeline_archetypes", maxTicks = 50) @Override
    public void twoWeaponsShareSurplusStatsButUseDifferentCurvesAndPhysicalReloads(GameTestHelper h) throws Exception { super.twoWeaponsShareSurplusStatsButUseDifferentCurvesAndPhysicalReloads(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:pipeline_query", maxTicks = 30) @Override
    public void calculatedPipelineSurvivesSourceRemovalAndAbilityChangeBeforeDelayedHealing(GameTestHelper h) throws Exception { super.calculatedPipelineSurvivesSourceRemovalAndAbilityChangeBeforeDelayedHealing(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:pipeline_animation", maxTicks = 35) @Override
    public void animationStageRunsAfterCurveAndAcceptedPlanRetainsAllTraces(GameTestHelper h) throws Exception { super.animationStageRunsAfterCurveAndAcceptedPlanRetainsAllTraces(h); }
}
