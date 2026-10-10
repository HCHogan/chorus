package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DamageGroupGameTest extends com.imdomestic.chorus.test.DamageGroupGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void rawAndSnapshotComponentsShareOneConsumedBuffButIndependentDamageUsesCurrentState(GameTestHelper h) throws Exception { super.rawAndSnapshotComponentsShareOneConsumedBuffButIndependentDamageUsesCurrentState(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void failedFirstWorldHitDoesNotReserveEligibilityAwayFromTheNextIndependentHit(GameTestHelper h) throws Exception { super.failedFirstWorldHitDoesNotReserveEligibilityAwayFromTheNextIndependentHit(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void lostSecondReceiptKeepsFirstConsumptionAndNeverReplaysEitherActualDamage(GameTestHelper h) throws Exception { super.lostSecondReceiptKeepsFirstConsumptionAndNeverReplaysEitherActualDamage(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:damage_group_delayed", maxTicks = 30) @Override
    public void delayedMemberRetainsOwnBuffEligibilityAfterConsumptionAndClosesOnRealTick(GameTestHelper h) throws Exception { super.delayedMemberRetainsOwnBuffEligibilityAfterConsumptionAndClosesOnRealTick(h); }
}
