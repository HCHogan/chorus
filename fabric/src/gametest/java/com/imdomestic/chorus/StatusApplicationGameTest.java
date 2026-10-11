package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class StatusApplicationGameTest extends com.imdomestic.chorus.test.StatusApplicationGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:status_applicants", maxTicks = 20) @Override
    public void realReapplicationsCreditEachApplicantWithoutChangingTheSharedStatesFirstOrigin(GameTestHelper h) throws Exception { super.realReapplicationsCreditEachApplicantWithoutChangingTheSharedStatesFirstOrigin(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:status_rejected", maxTicks = 20) @Override
    public void deniedDeadAndMissingWorldTargetsDoNotProduceApplicationRewards(GameTestHelper h) throws Exception { super.deniedDeadAndMissingWorldTargetsDoNotProduceApplicationRewards(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:status_authority", maxTicks = 20) @Override
    public void directGrantQualificationAndTimerRefreshRemainDistinctFromAnActualAppliedStatus(GameTestHelper h) throws Exception { super.directGrantQualificationAndTimerRefreshRemainDistinctFromAnActualAppliedStatus(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:status_unknown", maxTicks = 20) @Override
    public void unknownDownstreamHealingKeepsCommittedStatusAndHealthWithoutReapplying(GameTestHelper h) throws Exception { super.unknownDownstreamHealingKeepsCommittedStatusAndHealthWithoutReapplying(h); }
}
