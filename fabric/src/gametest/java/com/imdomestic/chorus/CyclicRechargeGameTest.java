package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class CyclicRechargeGameTest extends com.imdomestic.chorus.test.CyclicRechargeGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void onePacketPermitsTwoActualServerAbilityUsesAndExposesFinalBalancesToHealing(GameTestHelper h){super.onePacketPermitsTwoActualServerAbilityUsesAndExposesFinalBalancesToHealing(h);}
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:cyclic_ticks", maxTicks=35) @Override
    public void realTicksResumeTheRemainingFractionAndStopAfterFillingTheUsableAccount(GameTestHelper h){super.realTicksResumeTheRemainingFractionAndStopAfterFillingTheUsableAccount(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void currentRecipientPolicyAndDuplicateCompletionSourcesDoNotMultiplyThePacket(GameTestHelper h){super.currentRecipientPolicyAndDuplicateCompletionSourcesDoNotMultiplyThePacket(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownWorldObservationRetainsActualHealingAndBothResourceBalances(GameTestHelper h){super.unknownWorldObservationRetainsActualHealingAndBothResourceBalances(h);}
}
