package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EventEntityObservationGameTest extends com.imdomestic.chorus.test.EventEntityObservationGameTest {
    @net.fabricmc.fabric.api.gametest.v1.GameTest(structure = "chorus_gametest:empty") @Override
    public void confirmedNativeDamageKeepsTheAttackersObservedSprintAfterTheyStop(net.minecraft.gametest.framework.GameTestHelper h)throws Exception {super.confirmedNativeDamageKeepsTheAttackersObservedSprintAfterTheyStop(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void managedAndNativeKillsReadReceiptAfterDeathReactionDiscardsTheCorpse(GameTestHelper h)throws Exception{super.managedAndNativeKillsReadReceiptAfterDeathReactionDiscardsTheCorpse(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void retainedVictimLogicalAliasesAndUnavailableOwnersHaveExplicitEvidence(GameTestHelper h)throws Exception{super.retainedVictimLogicalAliasesAndUnavailableOwnersHaveExplicitEvidence(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void observationFailureCleansNativeScopeAndReportsCommittedDamageWithoutReplay(GameTestHelper h){super.observationFailureCleansNativeScopeAndReportsCommittedDamageWithoutReplay(h);}
}
