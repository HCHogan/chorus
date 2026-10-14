package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WintersShroudGameTest extends com.imdomestic.chorus.test.WintersShroudGameTest {
    @GameTest(structure="chorus_gametest:firebolt", environment="chorus_gametest:winters_shroud", maxTicks=50) @Override
    public void acceptedDodgeUsesNineMeterEnemyBoundaryAndDuranceAndSingleAspectPulse(GameTestHelper h) { super.acceptedDodgeUsesNineMeterEnemyBoundaryAndDuranceAndSingleAspectPulse(h); }
    @GameTest(structure="chorus_gametest:firebolt", environment="chorus_gametest:winters_shroud", maxTicks=50) @Override
    public void ascensionTriggersPrismaticPulseBeforeMotionAndReusesAllSharedStatuses(GameTestHelper h) { super.ascensionTriggersPrismaticPulseBeforeMotionAndReusesAllSharedStatuses(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:winters_shroud_regeneration", maxTicks=92) @Override
    public void realTicksIntegrateAdditionalBaseRateThenExpireWhileDetachedAspectDoesNotEraseGrantedBenefits(GameTestHelper h) throws Exception { super.realTicksIntegrateAdditionalBaseRateThenExpireWhileDetachedAspectDoesNotEraseGrantedBenefits(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:winters_shroud") @Override
    public void deniedSlowCannotAwardRegenerationButClassUsageStillHalvesCombatantHealthLoss(GameTestHelper h) throws Exception { super.deniedSlowCannotAwardRegenerationButClassUsageStillHalvesCombatantHealthLoss(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:winters_shroud_switch", maxTicks=55) @Override
    public void changingCurrentDodgeRoutesBonusToNewAccountAndClearingStopsItsRecovery(GameTestHelper h) throws Exception { super.changingCurrentDodgeRoutesBonusToNewAccountAndClearingStopsItsRecovery(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:winters_shroud") @Override
    public void unknownSlowAuthorizationKeepsPaidEnergyAndDefenseAndNeverRetries(GameTestHelper h) throws Exception { super.unknownSlowAuthorizationKeepsPaidEnergyAndDefenseAndNeverRetries(h); }
}
