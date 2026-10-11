package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DragonflyGameTest extends com.imdomestic.chorus.test.DragonflyGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:dragonfly_variants", maxTicks = 100) @Override
    public void realPrecisionKillsSelectNormalAndEnhancedElementalExplosions(GameTestHelper h) throws Exception { super.realPrecisionKillsSelectNormalAndEnhancedElementalExplosions(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:dragonfly_eligibility", maxTicks = 100) @Override
    public void physicalBodyUnclassifiedAndRemovedPerkKillsCannotTriggerWhileStowedHeadshotsCan(GameTestHelper h) throws Exception { super.physicalBodyUnclassifiedAndRemovedPerkKillsCannotTriggerWhileStowedHeadshotsCan(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:dragonfly_history", maxTicks = 40) @Override
    public void capturedExplosionSurvivesCorpseRemovalAndSourceReplacement(GameTestHelper h) throws Exception { super.capturedExplosionSurvivesCorpseRemovalAndSourceReplacement(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:dragonfly_credit", maxTicks = 40) @Override
    public void actualExplosionKillDoesNotBecomeAPrecisionKillOrTriggerAnotherDragonfly(GameTestHelper h) throws Exception { super.actualExplosionKillDoesNotBecomeAPrecisionKillOrTriggerAnotherDragonfly(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:dragonfly_unknown", maxTicks = 40) @Override
    public void unknownNativeExplosionResultRetainsDamageWithoutReplay(GameTestHelper h) throws Exception { super.unknownNativeExplosionResultRetainsDamageWithoutReplay(h); }
}
