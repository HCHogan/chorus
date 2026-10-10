package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FreezeGameTest extends com.imdomestic.chorus.test.FreezeGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_actions") @Override
    public void frozenCombatantsLoseNativeShotsMeleeAndBothAxesWhileBossesKeepActing(GameTestHelper h){super.frozenCombatantsLoseNativeShotsMeleeAndBothAxesWhileBossesKeepActing(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_expiry",maxTicks=140) @Override
    public void bossAutoShattersAtThreeSecondsButOrdinaryExpiryAndCleanseOnlyThaw(GameTestHelper h){super.bossAutoShattersAtThreeSecondsButOrdinaryExpiryAndCleanseOnlyThaw(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_guardian",maxTicks=110) @Override
    public void realGuardianDurationsFollowCasterAndRoamingSuperInsteadOfWorldMode(GameTestHelper h){super.realGuardianDurationsFollowCasterAndRoamingSuperInsteadOfWorldMode(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_super") @Override
    public void realGuardianGroundedSuperThawsButAirborneSuperAndClassInputAreDenied(GameTestHelper h){super.realGuardianGroundedSuperThawsButAirborneSuperAndClassInputAreDenied(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_chain") @Override
    public void actualLossReachesThresholdAndShatterDamageCanShatterAnotherFrozenTarget(GameTestHelper h){super.actualLossReachesThresholdAndShatterDamageCanShatterAnotherFrozenTarget(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_position") @Override
    public void lethalReceiptKeepsBlastCenterAfterOriginalVictimIsRemoved(GameTestHelper h){super.lethalReceiptKeepsBlastCenterAfterOriginalVictimIsRemoved(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_fault",maxTicks=80) @Override
    public void unknownShatterResultKeepsCommittedHealthAndNeverReplaysExplosion(GameTestHelper h){super.unknownShatterResultKeepsCommittedHealthAndNeverReplaysExplosion(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:freeze_rejection") @Override
    public void missingRankOrDeniedStatusCannotBlockActualCombatantActions(GameTestHelper h){super.missingRankOrDeniedStatusCannotBlockActualCombatantActions(h);}
}
