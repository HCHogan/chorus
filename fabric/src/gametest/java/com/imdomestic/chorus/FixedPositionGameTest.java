package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FixedPositionGameTest extends com.imdomestic.chorus.test.FixedPositionGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void positionCaptureUsesFeetAcceptsDeadAndReportsMissingEntities(GameTestHelper h) { super.positionCaptureUsesFeetAcceptsDeadAndReportsMissingEntities(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void fixedPointSelectionUsesItsWorldAndFiltersWithoutAnOriginEntity(GameTestHelper h) { super.fixedPointSelectionUsesItsWorldAndFiltersWithoutAnOriginEntity(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:fixed_position") @Override
    public void jsonRepeatedBlastsKeepActivationPointAfterOriginRemovalAndRefreshMembership(GameTestHelper h) throws Exception { super.jsonRepeatedBlastsKeepActivationPointAfterOriginRemovalAndRefreshMembership(h); }
}
