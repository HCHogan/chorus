package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeMotionGameTest extends com.imdomestic.chorus.test.NativeMotionGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_motion_all") @Override
    public void fullConstraintsBlockDirectVelocityRawPositionAndAllMovementCauses(GameTestHelper h){super.fullConstraintsBlockDirectVelocityRawPositionAndAllMovementCauses(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_motion_height") @Override
    public void heightLockKeepsHorizontalMotionAndCannotStepThroughASlab(GameTestHelper h){super.heightLockKeepsHorizontalMotionAndCannotStepThroughASlab(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_motion_packets") @Override
    public void forgedPositionPacketsAreCorrectedWhileFreeAxesStillReceiveVanillaValidation(GameTestHelper h) throws Exception{super.forgedPositionPacketsAreCorrectedWhileFreeAxesStillReceiveVanillaValidation(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_motion_impulse") @Override
    public void impulseReceiptsObserveClampedAxesWithoutFalseSuccessOrGrace(GameTestHelper h){super.impulseReceiptsObserveClampedAxesWithoutFalseSuccessOrGrace(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_motion_expiry",maxTicks=18) @Override
    public void expiryRetainsIndependentAxesAndReleasedForcesDoNotAccumulate(GameTestHelper h){super.expiryRetainsIndependentAxesAndReleasedForcesDoNotAccumulate(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_motion_teleport") @Override
    public void explicitAbsoluteAndRelativeTeleportsReanchorWithoutRemovingRestrictions(GameTestHelper h){super.explicitAbsoluteAndRelativeTeleportsReanchorWithoutRemovingRestrictions(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_motion_riding") @Override
    public void constraintDismountsRecipientsAndRejectsRemountUntilReleased(GameTestHelper h){super.constraintDismountsRecipientsAndRejectsRemountUntilReleased(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_motion_ai",maxTicks=100) @Override
    public void ghastDirectVelocityAiCannotMoveTheAnchorAndResumesAfterRelease(GameTestHelper h){super.ghastDirectVelocityAiCannotMoveTheAnchorAndResumesAfterRelease(h);}
}
