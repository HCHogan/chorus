package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AbilityInputGameTest extends com.imdomestic.chorus.test.AbilityInputGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void heldInputChecksTheResolvedVariantBeforeApplyingActionRestrictions(GameTestHelper h) { super.heldInputChecksTheResolvedVariantBeforeApplyingActionRestrictions(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void shortReleaseCastsOnceAndDirectUseCancelsAnOutstandingGesture(GameTestHelper h) { super.shortReleaseCastsOnceAndDirectUseCancelsAnOutstandingGesture(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:ability_input_hold", maxTicks=25) @Override
    public void serverTicksMeasureHoldAndRepeatedOrMismatchedEdgesCannotRestartOrReleaseIt(GameTestHelper h) { super.serverTicksMeasureHoldAndRepeatedOrMismatchedEdgesCannotRestartOrReleaseIt(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void selectionChangesCancellationAndDisconnectDiscardPendingInputWithoutCost(GameTestHelper h) { super.selectionChangesCancellationAndDisconnectDiscardPendingInputWithoutCost(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:ability_input_gate", maxTicks=20) @Override
    public void temporaryActionRestrictionCancelsAChargeEvenAfterTheRestrictionExpires(GameTestHelper h) { super.temporaryActionRestrictionCancelsAChargeEvenAfterTheRestrictionExpires(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void deathSpectatorAndRuntimeReplacementCannotTransferHeldInputs(GameTestHelper h) { super.deathSpectatorAndRuntimeReplacementCannotTransferHeldInputs(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void packetSequenceDimensionAndGestureChecksRunBeforeAnyAbilityPayment(GameTestHelper h) { super.packetSequenceDimensionAndGestureChecksRunBeforeAnyAbilityPayment(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownReleasedWorldResultRetainsCostAndConsumesGestureAndPacketSequence(GameTestHelper h) { super.unknownReleasedWorldResultRetainsCostAndConsumesGestureAndPacketSequence(h); }
}
