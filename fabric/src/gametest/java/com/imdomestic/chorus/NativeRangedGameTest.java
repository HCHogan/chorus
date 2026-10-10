package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeRangedGameTest extends com.imdomestic.chorus.test.NativeRangedGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_ranged_methods") @Override
    public void allRangedAttackMobImplementationsRespectTheGateBeforeSpendingCrossbowAmmo(GameTestHelper h)throws Exception{super.allRangedAttackMobImplementationsRespectTheGateBeforeSpendingCrossbowAmmo(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_ranged_goals") @Override
    public void privateProjectileGoalsAndWitherSideHeadsCannotBypassRestrictions(GameTestHelper h)throws Exception{super.privateProjectileGoalsAndWitherSideHeadsCannotBypassRestrictions(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_ranged_beam") @Override
    public void guardianBeamIsGatedWhileBlazeMeleeRemainsAvailable(GameTestHelper h)throws Exception{super.guardianBeamIsGatedWhileBlazeMeleeRemainsAvailable(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_ranged_brains") @Override
    public void brainAttacksRejectStartsAndStopChargingBeforeActualEmission(GameTestHelper h)throws Exception{super.brainAttacksRejectStartsAndStopChargingBeforeActualEmission(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_ranged_dragon") @Override
    public void dragonStrafeFinishesItsDeniedAttemptAndCanShootOnALaterPass(GameTestHelper h)throws Exception{super.dragonStrafeFinishesItsDeniedAttemptAndCanShootOnALaterPass(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_ranged_expiry",maxTicks=18) @Override
    public void aRecipientBuffExpiresOnTheWorldClockAndPreservesCasterAttribution(GameTestHelper h)throws Exception{super.aRecipientBuffExpiresOnTheWorldClockAndPreservesCasterAttribution(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_ranged_failure") @Override
    public void failedQueriesRejectThatAttemptWithoutFreezingUnrestrictedMobsOrDetachedVanilla(GameTestHelper h)throws Exception{super.failedQueriesRejectThatAttemptWithoutFreezingUnrestrictedMobsOrDetachedVanilla(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_ranged_ai",maxTicks=150) @Override
    public void autonomousSkeletonCannotFireButItsOldArrowHitsAndShootingResumes(GameTestHelper h)throws Exception{super.autonomousSkeletonCannotFireButItsOldArrowHitsAndShootingResumes(h);}
}
