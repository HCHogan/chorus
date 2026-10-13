package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class BuffApplicationGameTest extends com.imdomestic.chorus.test.BuffApplicationGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:buff_applicants", maxTicks = 100) @Override
    public void directAndAuthorizedReapplicationsRewardEachActualApplicant(GameTestHelper h) { super.directAndAuthorizedReapplicationsRewardEachActualApplicant(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:buff_authority", maxTicks = 100) @Override
    public void qualificationTimerChangesAndDeniedStatusesDoNotImpersonateAGrant(GameTestHelper h) { super.qualificationTimerChangesAndDeniedStatusesDoNotImpersonateAGrant(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:buff_unknown", maxTicks = 100) @Override
    public void unknownRewardResultPreservesCommittedGrantAndActualHealthWithoutReplay(GameTestHelper h) { super.unknownRewardResultPreservesCommittedGrantAndActualHealthWithoutReplay(h); }
}
