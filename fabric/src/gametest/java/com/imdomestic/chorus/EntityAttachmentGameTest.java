package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EntityAttachmentGameTest {
    @GameTest(environment = "chorus_gametest:attachment_death", maxTicks = 25)
    public void fixedDeathUsesTheConfirmedPointBeforeCorpseCallbacksAndSurvivesSourceRemoval(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityAttachmentGameTest().fixedDeathUsesTheConfirmedPointBeforeCorpseCallbacksAndSurvivesSourceRemoval(h); }
    @GameTest(environment = "chorus_gametest:attachment_query", maxTicks = 25)
    public void removalDuringTheWorldQueryCommitsBeforeTheDependentContinuation(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityAttachmentGameTest().removalDuringTheWorldQueryCommitsBeforeTheDependentContinuation(h); }
    @GameTest(environment = "chorus_gametest:attachment_reload", maxTicks = 25)
    public void waitingAttachmentResumesOnlyTheLifeRestoredFromNativeEntityNbt(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityAttachmentGameTest().waitingAttachmentResumesOnlyTheLifeRestoredFromNativeEntityNbt(h); }
    @GameTest
    public void aNewAttachmentCreatedAfterACommittedDeathIsNotClosedByItsQueuedNotification(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityAttachmentGameTest().aNewAttachmentCreatedAfterACommittedDeathIsNotClosedByItsQueuedNotification(h); }
    @GameTest
    public void queuedRemovalCommitsBeforeAdvancingPastItsFuse(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityAttachmentGameTest().queuedRemovalCommitsBeforeAdvancingPastItsFuse(h); }
    @GameTest
    public void failedOlderReactionRetainsAllLaterQueuedRemovalEvidence(GameTestHelper h) throws Exception { new com.imdomestic.chorus.test.EntityAttachmentGameTest().failedOlderReactionRetainsAllLaterQueuedRemovalEvidence(h); }
}
