package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SuspendGameTest extends com.imdomestic.chorus.test.SuspendGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:suspend_actions") @Override
    public void tieredSuspensionBlocksActualCombatantShotsAndMeleeButBossesRemainActive(GameTestHelper h){super.tieredSuspensionBlocksActualCombatantShotsAndMeleeButBossesRemainActive(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:suspend_durations",maxTicks=185) @Override
    public void realRankDurationsAndApplierContinuityExpireAtThreeFourSixAndEightSeconds(GameTestHelper h){super.realRankDurationsAndApplierContinuityExpireAtThreeFourSixAndEightSeconds(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:suspend_boss",maxTicks=35) @Override
    public void bossHasAnObservableDebuffWithoutControlThenTakesOneActualThreeHundredDamageSnap(GameTestHelper h){super.bossHasAnObservableDebuffWithoutControlThenTakesOneActualThreeHundredDamageSnap(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:suspend_rejection") @Override
    public void unknownOrDeniedRecipientCannotBeLiftedOrHaveActionsRestricted(GameTestHelper h){super.unknownOrDeniedRecipientCannotBeLiftedOrHaveActionsRestricted(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:suspend_guardian",maxTicks=55) @Override
    public void realGuardianUsesTwoSecondsKeepsHorizontalMovementAndLosesOnlyVerticalMotion(GameTestHelper h){super.realGuardianUsesTwoSecondsKeepsHorizontalMovementAndLosesOnlyVerticalMotion(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:suspend_fault",maxTicks=35) @Override
    public void unknownBossDamageResultKeepsCommittedHealthAndNeverRepeatsTheSnap(GameTestHelper h){super.unknownBossDamageResultKeepsCommittedHealthAndNeverRepeatsTheSnap(h);}
}
