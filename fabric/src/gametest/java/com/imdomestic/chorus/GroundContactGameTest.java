package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class GroundContactGameTest extends com.imdomestic.chorus.test.GroundContactGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ground_contact_native", maxTicks = 60) @Override
    public void actualNativeCollisionEmitsOnceAndDelayedReadersKeepTheContactAfterTheEntityMoves(GameTestHelper h) { super.actualNativeCollisionEmitsOnceAndDelayedReadersKeepTheContactAfterTheEntityMoves(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ground_contact_flags", maxTicks = 60) @Override
    public void flagsTeleportAndStandingMovementCannotInventAContactButAnotherRealDescentCan(GameTestHelper h) { super.flagsTeleportAndStandingMovementCannotInventAContactButAnotherRealDescentCan(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ground_contact_player", maxTicks = 60) @Override
    public void acceptedPlayerPacketProducesOnePhysicalLandingEvenIfItsGroundFlagWasAlreadyTrue(GameTestHelper h) { super.acceptedPlayerPacketProducesOnePhysicalLandingEvenIfItsGroundFlagWasAlreadyTrue(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ground_contact_rejected", maxTicks = 60) @Override
    public void collisionDuringARejectedPlayerPacketDoesNotPublishAProvisionalLanding(GameTestHelper h) { super.collisionDuringARejectedPlayerPacketDoesNotPublishAProvisionalLanding(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ground_contact_managed", maxTicks = 60) @Override
    public void nativeMovementInsideAWorldOperationQueuesItsFactAfterTheOwningRuleFrame(GameTestHelper h) { super.nativeMovementInsideAWorldOperationQueuesItsFactAfterTheOwningRuleFrame(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ground_contact_unknown", maxTicks = 60) @Override
    public void unknownLandingReactionRetainsThePhysicalLandingWithoutReplayingItsCue(GameTestHelper h) { super.unknownLandingReactionRetainsThePhysicalLandingWithoutReplayingItsCue(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ground_contact_replaced", maxTicks = 60) @Override
    public void historicalLandingCannotResolveToANewLifeWithTheSameUuid(GameTestHelper h) { super.historicalLandingCannotResolveToANewLifeWithTheSameUuid(h); }
}
