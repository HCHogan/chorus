package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FinisherGameTest extends com.imdomestic.chorus.test.FinisherGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:finisher_completed", maxTicks = 40) @Override
    public void delayedStrikeKillsAndPreservesHistoryAfterCorpseRemovalWhileBothParticipantsAreLocked(GameTestHelper h) throws Exception { super.delayedStrikeKillsAndPreservesHistoryAfterCorpseRemovalWhileBothParticipantsAreLocked(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:finisher_race", maxTicks = 40) @Override
    public void twoActualActorsCannotReserveTheSameVictimAndAnActorCannotReserveTwice(GameTestHelper h) throws Exception { super.twoActualActorsCannotReserveTheSameVictimAndAnActorCannotReserveTwice(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:finisher_rejected", maxTicks = 20) @Override
    public void HealthTagsPlayersSelfDistanceAndObstructionsAreCheckedInTheActualWorld(GameTestHelper h) throws Exception { super.HealthTagsPlayersSelfDistanceAndObstructionsAreCheckedInTheActualWorld(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:finisher_allied", maxTicks = 20) @Override
    public void FriendlyTeamMembersCannotBeFinishedEvenWithAnEligibilityTag(GameTestHelper h) throws Exception { super.FriendlyTeamMembersCannotBeFinishedEvenWithAnEligibilityTag(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:finisher_escape", maxTicks = 40) @Override
    public void ATargetLeavingTheAcceptedRangeCancelsAndReleasesTheActor(GameTestHelper h) throws Exception { super.ATargetLeavingTheAcceptedRangeCancelsAndReleasesTheActor(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:finisher_missing", maxTicks = 40) @Override
    public void ARemovedTargetReleasesItsActorWithoutTurningDisappearanceIntoAKill(GameTestHelper h) throws Exception { super.ARemovedTargetReleasesItsActorWithoutTurningDisappearanceIntoAKill(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:finisher_prevented", maxTicks = 40) @Override
    public void ActualDeathProtectionConsumesTheTotemWithoutPublishingFinisherCompletion(GameTestHelper h) throws Exception { super.ActualDeathProtectionConsumesTheTotemWithoutPublishingFinisherCompletion(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:finisher_unknown", maxTicks = 40) @Override
    public void UnknownReceiptAfterPhysicalDeathStopsWithoutCompletionOrAnotherStrike(GameTestHelper h) throws Exception { super.UnknownReceiptAfterPhysicalDeathStopsWithoutCompletionOrAnotherStrike(h); }
}
