package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FinisherInputGameTest extends com.imdomestic.chorus.test.FinisherInputGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:finisher_input_selection", maxTicks=40) @Override
    public void unprivilegedCommandSkipsIneligibleNearestAndSelectsOneEligibleForwardTarget(GameTestHelper h) throws Exception { super.unprivilegedCommandSkipsIneligibleNearestAndSelectsOneEligibleForwardTarget(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:finisher_input_packets", maxTicks=40) @Override
    public void sequenceDimensionCodecAndBusyInputsCannotReplayOrQueueAnotherFinisher(GameTestHelper h) throws Exception { super.sequenceDimensionCodecAndBusyInputsCannotReplayOrQueueAnotherFinisher(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:finisher_input_ability", maxTicks=40) @Override
    public void independentFinisherInputDoesNotBecomeAnOrdinaryAbilityOrRequireItsEnergyAccount(GameTestHelper h) throws Exception { super.independentFinisherInputDoesNotBecomeAnOrdinaryAbilityOrRequireItsEnergyAccount(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:finisher_input_ineligible", maxTicks=20) @Override
    public void deadAndSpectatorSendersCannotRequestServerEffects(GameTestHelper h) throws Exception { super.deadAndSpectatorSendersCannotRequestServerEffects(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:finisher_input_unknown", maxTicks=20) @Override
    public void unknownStartReactionConsumesThePacketAndCannotReplayItsCommittedReservation(GameTestHelper h) throws Exception { super.unknownStartReactionConsumesThePacketAndCannotReplayItsCommittedReservation(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:finisher_input_binding", maxTicks=40) @Override
    public void missingContentCannotInventAnExecutionAndUnbindingDoesNotUndoAnAcceptedOne(GameTestHelper h) throws Exception { super.missingContentCannotInventAnExecutionAndUnbindingDoesNotUndoAnAcceptedOne(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:finisher_input_aim", maxTicks=40) @Override
    public void targetSelectionUsesCurrentServerFacingRatherThanAClientTargetIdentity(GameTestHelper h) throws Exception { super.targetSelectionUsesCurrentServerFacingRatherThanAClientTargetIdentity(h); }
}
