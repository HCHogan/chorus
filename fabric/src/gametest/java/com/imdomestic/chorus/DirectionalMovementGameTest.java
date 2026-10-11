package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DirectionalMovementGameTest extends com.imdomestic.chorus.test.DirectionalMovementGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:directional_vectors", maxTicks=20) @Override
    public void forwardDiagonalsUseNormalizedInputAndKeepTheirDirectionAcrossYaw(GameTestHelper h) throws Exception { super.forwardDiagonalsUseNormalizedInputAndKeepTheirDirectionAcrossYaw(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:directional_momentum", maxTicks=20) @Override
    public void inputAccelerationPreservesExternalMomentumAndCannotBypassMovementDenial(GameTestHelper h) throws Exception { super.inputAccelerationPreservesExternalMomentumAndCannotBypassMovementDenial(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:directional_modes", maxTicks=20) @Override
    public void airborneFlyingRidingSpectatorAndDeadActorsDoNotGetGroundAcceleration(GameTestHelper h) throws Exception { super.airborneFlyingRidingSpectatorAndDeadActorsDoNotGetGroundAcceleration(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:directional_fluid", maxTicks=20) @Override
    public void actualWaterAndLavaImmersionDoNotReceiveTheGroundBonus(GameTestHelper h) throws Exception { super.actualWaterAndLavaImmersionDoNotReceiveTheGroundBonus(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:directional_travel", maxTicks=20) @Override
    public void nativeTravelActuallyMovesFurtherOnlyWhenWalkingForward(GameTestHelper h) throws Exception { super.nativeTravelActuallyMovesFurtherOnlyWhenWalkingForward(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:directional_expiry", maxTicks=15) @Override
    public void recipientExpiryAndSourceRemovalKeepIndependentContributions(GameTestHelper h) throws Exception { super.recipientExpiryAndSourceRemovalKeepIndependentContributions(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:directional_native_sync", maxTicks=20) @Override
    public void nativeAttributePacketsCarryTheProjectionAndRemovalWithoutOverwritingOtherModifiers(GameTestHelper h) throws Exception { super.nativeAttributePacketsCarryTheProjectionAndRemovalWithoutOverwritingOtherModifiers(h); }
}
