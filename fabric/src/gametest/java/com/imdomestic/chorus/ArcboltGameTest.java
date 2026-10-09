package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ArcboltGameTest extends com.imdomestic.chorus.test.ArcboltGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:arcbolt_chain", maxTicks = 60) @Override
    public void realTickLocksVisibleTargetAndChainsFromMovedLethalHitThroughFourDistinctEnemies(GameTestHelper h) throws Exception { super.realTickLocksVisibleTargetAndChainsFromMovedLethalHitThroughFourDistinctEnemies(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:arcbolt_cancel", maxTicks = 60) @Override
    public void cancelledSecondHitStopsChainWithoutRetargetingOrHurtingTheThirdEnemy(GameTestHelper h) throws Exception { super.cancelledSecondHitStopsChainWithoutRetargetingOrHurtingTheThirdEnemy(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:arcbolt_missing", maxTicks = 60) @Override
    public void removedFirstTargetProducesNoDamageAndCannotBeReplacedByALateEnemy(GameTestHelper h) throws Exception { super.removedFirstTargetProducesNoDamageAndCannotBeReplacedByALateEnemy(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:arcbolt_visibility", maxTicks = 60) @Override
    public void lateWallDoesNotRewriteFirstScanAndSubsequentHopsUseExplicitNoSightPolicy(GameTestHelper h) throws Exception { super.lateWallDoesNotRewriteFirstScanAndSubsequentHopsUseExplicitNoSightPolicy(h); }
}
