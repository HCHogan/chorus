package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class BuffRemovalGameTest extends com.imdomestic.chorus.test.BuffRemovalGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:buff_removal_lifetime",maxTicks=14) @Override
    public void removingSeveralDefinitionsCommitsBeforeCleanupAndKeepsDetachedNativeHealing(GameTestHelper h){super.removingSeveralDefinitionsCommitsBeforeCleanupAndKeepsDetachedNativeHealing(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:buff_removal_failure",maxTicks=14) @Override
    public void anUnknownCleanupOutcomeKeepsTheWholeBatchRemovedAndDoesNotRepeatHealing(GameTestHelper h){super.anUnknownCleanupOutcomeKeepsTheWholeBatchRemovedAndDoesNotRepeatHealing(h);}
}
