package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AbilityRechargeGameTest extends com.imdomestic.chorus.test.AbilityRechargeGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void physicalPugilistKillCompletesTheCycleAndEnablesTwoActualCasts(GameTestHelper h) throws Exception { super.physicalPugilistKillCompletesTheCycleAndEnablesTwoActualCasts(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void anInFlightWeaponKillUsesTheNewlySelectedRechargeDestination(GameTestHelper h) throws Exception { super.anInFlightWeaponKillUsesTheNewlySelectedRechargeDestination(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void replacementRefundReturnsToThePaidUseAccountWhileKillsStillFeedBaseRecharge(GameTestHelper h) throws Exception { super.replacementRefundReturnsToThePaidUseAccountWhileKillsStillFeedBaseRecharge(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownCompletionAfterAPhysicalKillKeepsActualHealthAndBothAccountWrites(GameTestHelper h) throws Exception { super.unknownCompletionAfterAPhysicalKillKeepsActualHealthAndBothAccountWrites(h); }
}
