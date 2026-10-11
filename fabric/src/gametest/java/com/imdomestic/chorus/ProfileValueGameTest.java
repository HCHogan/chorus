package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProfileValueGameTest extends com.imdomestic.chorus.test.ProfileValueGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:profile_value_delay", maxTicks=30) @Override
    public void countDerivedDelayRetainsCapturedHealingAndReadsTheCurrentOwnerAfterSourcesAreRemoved(GameTestHelper h) throws Exception { super.countDerivedDelayRetainsCapturedHealingAndReadsTheCurrentOwnerAfterSourcesAreRemoved(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:profile_value_damage", maxTicks=20) @Override
    public void nativeDamageKeepsCapturedOwnerCopiesAndRechecksEachVictimsCurrentCopies(GameTestHelper h) throws Exception { super.nativeDamageKeepsCapturedOwnerCopiesAndRechecksEachVictimsCurrentCopies(h); }
}
