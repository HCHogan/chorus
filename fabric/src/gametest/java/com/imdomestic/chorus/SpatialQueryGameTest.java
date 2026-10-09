package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SpatialQueryGameTest extends com.imdomestic.chorus.test.SpatialQueryGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void lineOfSightFiltersBeforeNearestLimitAndCanBeExplicitlyDisabled(GameTestHelper h) { super.lineOfSightFiltersBeforeNearestLimitAndCanBeExplicitlyDisabled(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void collisionShapesFluidsAndUnloadedTerrainHaveExplicitVisibilitySemantics(GameTestHelper h) { super.collisionShapesFluidsAndUnloadedTerrainHaveExplicitVisibilitySemantics(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void cylinderAnchorsMissingDirectionAndCrossDimensionDirectionAreObservedExplicitly(GameTestHelper h) { super.cylinderAnchorsMissingDirectionAndCrossDimensionDirectionAreObservedExplicitly(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void delayedConeUsesCapturedDirectionAndPositionAfterCasterTurnsAndDisappears(GameTestHelper h) throws Exception { super.delayedConeUsesCapturedDirectionAndPositionAfterCasterTurnsAndDisappears(h); }
}
