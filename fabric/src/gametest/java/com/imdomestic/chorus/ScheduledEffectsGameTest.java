package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Fabric registration for shared JSON schedule scenarios. */
public class ScheduledEffectsGameTest extends com.imdomestic.chorus.test.ScheduledEffectsGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void jsonPeriodicBuffRefreshPauseResumeAndRemovalControlRealHealing(GameTestHelper helper) throws Exception { super.jsonPeriodicBuffRefreshPauseResumeAndRemovalControlRealHealing(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void curePvpTimingUsesRuleEnvironmentAndCooldown(GameTestHelper helper) throws Exception { super.curePvpTimingUsesRuleEnvironmentAndCooldown(helper); }
    @GameTest(structure = "chorus_gametest:empty", dimension = "minecraft:the_end", maxTicks = 85) @Override
    public void serverTicksExecuteJsonCureRecoveryAndResourceProfiles(GameTestHelper helper) throws Exception { super.serverTicksExecuteJsonCureRecoveryAndResourceProfiles(helper); }
}
