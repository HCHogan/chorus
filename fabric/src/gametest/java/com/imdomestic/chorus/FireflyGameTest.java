package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FireflyGameTest extends com.imdomestic.chorus.test.FireflyGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:firefly_variants", maxTicks = 100) @Override
    public void physicalPrecisionKillsAlwaysProduceSolarAndOnlyEnhancedReloadIsStronger(GameTestHelper h) throws Exception { super.physicalPrecisionKillsAlwaysProduceSolarAndOnlyEnhancedReloadIsStronger(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:firefly_eligibility", maxTicks = 100) @Override
    public void bodyUnclassifiedAndRemovedPerkKillsFailWhileStowedAndUnlabelledHeadshotsWork(GameTestHelper h) throws Exception { super.bodyUnclassifiedAndRemovedPerkKillsFailWhileStowedAndUnlabelledHeadshotsWork(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:firefly_reload", maxTicks = 175) @Override
    public void realReloadTransfersReservesForBothVariantsAndStowedBuffStillExpires(GameTestHelper h) throws Exception { super.realReloadTransfersReservesForBothVariantsAndStowedBuffStillExpires(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:firefly_expiry", maxTicks = 160) @Override
    public void buffCanExpireDuringAnAcceptedReloadWithoutChangingItsCompletion(GameTestHelper h) throws Exception { super.buffCanExpireDuringAnAcceptedReloadWithoutChangingItsCompletion(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:firefly_history", maxTicks = 40) @Override
    public void sourceRemovalCleansReloadButPendingSolarBlastKeepsCenterAndBonus(GameTestHelper h) throws Exception { super.sourceRemovalCleansReloadButPendingSolarBlastKeepsCenterAndBonus(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:firefly_floor", maxTicks = 100) @Override
    public void explicitMinimumAppliesAtTheOuterEdgeButNeverOutsideTheRadius(GameTestHelper h) throws Exception { super.explicitMinimumAppliesAtTheOuterEdgeButNeverOutsideTheRadius(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:firefly_unknown", maxTicks = 40) @Override
    public void unknownExplosionOutcomeKeepsPriorReloadRewardAndActualDamageWithoutReplay(GameTestHelper h) throws Exception { super.unknownExplosionOutcomeKeepsPriorReloadRewardAndActualDamageWithoutReplay(h); }
}
