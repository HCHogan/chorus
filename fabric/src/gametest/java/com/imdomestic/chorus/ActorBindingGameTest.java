package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ActorBindingGameTest extends com.imdomestic.chorus.test.ActorBindingGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:actor_lifecycle",maxTicks=50) @Override
    public void nativeAttachReplaceAndAbilityChangesKeepOneStandingOwnerSource(GameTestHelper h) { super.nativeAttachReplaceAndAbilityChangesKeepOneStandingOwnerSource(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:actor_restore",maxTicks=50) @Override
    public void nativeCheckpointRestoresStandingRulesAndTimerWithoutReplayingAttachment(GameTestHelper h) { super.nativeCheckpointRestoresStandingRulesAndTimerWithoutReplayingAttachment(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:actor_unknown",maxTicks=40) @Override
    public void unknownNativeAttachmentHealKeepsEnrollmentAndDoesNotReplayOnRetry(GameTestHelper h) { super.unknownNativeAttachmentHealKeepsEnrollmentAndDoesNotReplayOnRetry(h); }
}
