package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeMovementGameTest extends com.imdomestic.chorus.test.NativeMovementGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_movement_velocity") @Override
    public void inputDenialPreservesVelocityGravityAndRealImpulseCommands(GameTestHelper h){super.inputDenialPreservesVelocityGravityAndRealImpulseCommands(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_movement_jumps") @Override
    public void groundAndLiquidOverridesCannotBypassJumpRestrictions(GameTestHelper h) throws Exception{super.groundAndLiquidOverridesCannotBypassJumpRestrictions(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_movement_expiry",maxTicks=18) @Override
    public void recipientExpiryCannotClearAnotherSourceAndKeepsCasterEvidence(GameTestHelper h){super.recipientExpiryCannotClearAnotherSourceAndKeepsCasterEvidence(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_movement_player") @Override
    public void serverFiltersHeldAndNewPlayerInputAndRuntimeCloseClearsOnlyItsState(GameTestHelper h){super.serverFiltersHeldAndNewPlayerInputAndRuntimeCloseClearsOnlyItsState(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_movement_failure") @Override
    public void unobservedConditionsStopTheRuntimeWithoutAuthorizingMovementOrInventingADecision(GameTestHelper h){super.unobservedConditionsStopTheRuntimeWithoutAuthorizingMovementOrInventingADecision(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_movement_ai",maxTicks=120) @Override
    public void autonomousAiKeepsTargetAndMeleeAndResumesWalkingAfterRemoval(GameTestHelper h){super.autonomousAiKeepsTargetAndMeleeAndResumesWalkingAfterRemoval(h);}
}
