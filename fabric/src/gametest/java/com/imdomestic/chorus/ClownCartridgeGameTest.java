package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ClownCartridgeGameTest extends com.imdomestic.chorus.test.ClownCartridgeGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:clown_reload", maxTicks = 25) @Override
    public void realReloadOverflowsFromReservesAndKeepsTwoWeaponInstancesAndEnhancedChoiceSeparate(GameTestHelper h) throws Exception { super.realReloadOverflowsFromReservesAndKeepsTwoWeaponInstancesAndEnhancedChoiceSeparate(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:clown_shortfall", maxTicks = 15) @Override
    public void completedReloadCanOnlyOverflowByTheRemainingFiniteReserve(GameTestHelper h) throws Exception { super.completedReloadCanOnlyOverflowByTheRemainingFiniteReserve(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:clown_unknown", maxTicks = 15) @Override
    public void unknownOverflowWorldReactionCannotRerollOrDuplicateAlreadyTransferredRounds(GameTestHelper h) throws Exception { super.unknownOverflowWorldReactionCannotRerollOrDuplicateAlreadyTransferredRounds(h); }
}
