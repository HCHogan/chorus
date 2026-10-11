package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ArmorChargeGameTest extends com.imdomestic.chorus.test.ArmorChargeGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_charge_pickup", maxTicks=20) @Override
    public void actualOrbContactsUseThreeToSixCapacityAndStacksOnStacksWithoutDuplicatingSuperEnergy(GameTestHelper h) throws Exception { super.actualOrbContactsUseThreeToSixCapacityAndStacksOnStacksWithoutDuplicatingSuperEnergy(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_charge_fonts", maxTicks=20) @Override
    public void allSixFontContributionsFollowActualPickupAndLiveModCopiesThenDisappearOnSpend(GameTestHelper h) throws Exception { super.allSixFontContributionsFollowActualPickupAndLiveModCopiesThenDisappearOnSpend(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_charge_decay", maxTicks=640) @Override
    public void realServerTicksDecayOneStackEveryTenSecondsAndNewOrbDoesNotResetTheRunningTimer(GameTestHelper h) throws Exception { super.realServerTicksDecayOneStackEveryTenSecondsAndNewOrbDoesNotResetTheRunningTimer(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_charge_dilation", maxTicks=440) @Override
    public void threeTimeDilationCopiesUseActualTwentySecondDecayRegardlessOfFontCopyCount(GameTestHelper h) throws Exception { super.threeTimeDilationCopiesUseActualTwentySecondDecayRegardlessOfFontCopyCount(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_charge_spend", maxTicks=330) @Override
    public void spendingRestartsDecayAndInsufficientCostCannotMoveItsDeadline(GameTestHelper h) throws Exception { super.spendingRestartsDecayAndInsufficientCostCannotMoveItsDeadline(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_charge_unbind", maxTicks=440) @Override
    public void removingTheLastPassiveStopsDecayWhileNonPassiveModsKeepThePool(GameTestHelper h) throws Exception { super.removingTheLastPassiveStopsDecayWhileNonPassiveModsKeepThePool(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_charge_starvation", maxTicks=20) @Override
    public void independentStarvationPermissionStillAllowsAFullChargeOrbAndOnlyItsOwnReward(GameTestHelper h) throws Exception { super.independentStarvationPermissionStillAllowsAFullChargeOrbAndOnlyItsOwnReward(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_charge_death", maxTicks=20) @Override
    public void actualPlayerDeathClearsChargesAndRejectsSubsequentGainUntilAlive(GameTestHelper h) throws Exception { super.actualPlayerDeathClearsChargesAndRejectsSubsequentGainUntilAlive(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:armor_charge_unknown", maxTicks=20) @Override
    public void unknownReactionAfterPaymentRetainsConsumedStacksWithoutReplayingTheCost(GameTestHelper h) throws Exception { super.unknownReactionAfterPaymentRetainsConsumedStacksWithoutReplayingTheCost(h); }
}
