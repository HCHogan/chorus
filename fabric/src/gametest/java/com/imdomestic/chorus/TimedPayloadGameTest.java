package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class TimedPayloadGameTest extends com.imdomestic.chorus.test.TimedPayloadGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:timed_delay",maxTicks=35) @Override
    public void actualBodyDamagePrecedesTheSeparateBlastByTwelveServerTicks(GameTestHelper h)throws Exception {super.actualBodyDamagePrecedesTheSeparateBlastByTwelveServerTicks(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:timed_move",maxTicks=35) @Override
    public void explosionFollowsTheOriginalContactOffsetAndObservesNeighborsWhenDue(GameTestHelper h)throws Exception {super.explosionFollowsTheOriginalContactOffsetAndObservesNeighborsWhenDue(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:timed_dead",maxTicks=35) @Override
    public void lethalDirectCanStillDetonateOnThePresentCorpseWithoutDamagingItAgain(GameTestHelper h)throws Exception {super.lethalDirectCanStillDetonateOnThePresentCorpseWithoutDamagingItAgain(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:timed_missing",maxTicks=35) @Override
    public void RemovedAttachmentTargetDoesNotExplodeAtAnInventedFallbackCenter(GameTestHelper h)throws Exception {super.RemovedAttachmentTargetDoesNotExplodeAtAnInventedFallbackCenter(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:timed_stowed",maxTicks=35) @Override
    public void delayedPrecisionAndSourceBonusRemainAfterUnequipAndOwnerMovement(GameTestHelper h)throws Exception {super.delayedPrecisionAndSourceBonusRemainAfterUnequipAndOwnerMovement(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:timed_wall",maxTicks=35) @Override
    public void delayedBlockAttachmentKeepsItsPhysicalSurfaceAfterTheBlockChanges(GameTestHelper h)throws Exception {super.delayedBlockAttachmentKeepsItsPhysicalSurfaceAfterTheBlockChanges(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:timed_unknown",maxTicks=35) @Override
    public void unknownDelayedDamageDoesNotReplayItsConsumedContinuation(GameTestHelper h)throws Exception {super.unknownDelayedDamageDoesNotReplayItsConsumedContinuation(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:timed_new_life",maxTicks=35) @Override
    public void sameUuidReplacementRetiresTheOldAttachmentBeforeQueryingBlastNeighbors(GameTestHelper h)throws Exception {super.sameUuidReplacementRetiresTheOldAttachmentBeforeQueryingBlastNeighbors(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:timed_damage_guard",maxTicks=35) @Override
    public void replacementAfterPositionResolutionIsRejectedAtTheNativeDamageBoundary(GameTestHelper h)throws Exception {super.replacementAfterPositionResolutionIsRejectedAtTheNativeDamageBoundary(h);}
}
