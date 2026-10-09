package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ContinuationGameTest extends com.imdomestic.chorus.test.ContinuationGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:continuation_damage") @Override
    public void jsonCaptureSurvivesDetachAndExpiryThenUsesActualDamageForHealing(GameTestHelper h) throws Exception { super.jsonCaptureSurvivesDetachAndExpiryThenUsesActualDamageForHealing(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:continuation_lifetime") @Override
    public void defaultSourceLifetimeCancelsThePendingBodyWhenUnequipped(GameTestHelper h) throws Exception { super.defaultSourceLifetimeCancelsThePendingBodyWhenUnequipped(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:continuation_area") @Override
    public void delayedAreaQueryUsesTheWorldAtExecutionInsteadOfTheWorldAtLaunch(GameTestHelper h) throws Exception { super.delayedAreaQueryUsesTheWorldAtExecutionInsteadOfTheWorldAtLaunch(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:continuation_burst") @Override
    public void nestedDelayedHitsKeepTheirSnapshotAndUseSeparateWorldOperations(GameTestHelper h) throws Exception { super.nestedDelayedHitsKeepTheirSnapshotAndUseSeparateWorldOperations(h); }
}
