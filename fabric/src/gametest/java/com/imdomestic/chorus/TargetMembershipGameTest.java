package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class TargetMembershipGameTest extends com.imdomestic.chorus.test.TargetMembershipGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:membership_movement", maxTicks = 12) @Override
    public void targetAndCenterMovementChangeMembershipOnceAndExpiryCleansActualHealing(GameTestHelper h) throws Exception { super.targetAndCenterMovementChangeMembershipOnceAndExpiryCleansActualHealing(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:membership_removal", maxTicks = 10) @Override
    public void overlappingSourcesCleanIndependentlyWhenMembersAndCenterDisappear(GameTestHelper h) throws Exception { super.overlappingSourcesCleanIndependentlyWhenMembersAndCenterDisappear(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:membership_snapshot", maxTicks = 10) @Override
    public void membershipUsesTheQueryReceiptAndNextPollObservesLaterMovement(GameTestHelper h) throws Exception { super.membershipUsesTheQueryReceiptAndNextPollObservesLaterMovement(h); }
}
