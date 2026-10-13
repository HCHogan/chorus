package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class BenevolenceGameTest extends com.imdomestic.chorus.test.BenevolenceGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:benevolence_acrobat", maxTicks = 100) @Override
    public void actualAcrobatCompletionAcceleratesAllThreeSelectedReceivingAccounts(GameTestHelper h) { super.actualAcrobatCompletionAcceleratesAllThreeSelectedReceivingAccounts(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:benevolence_cure", maxTicks = 100) @Override
    public void actualCureOverhealingQualifiesButOrdinaryHealingAndSelfGrantsDoNot(GameTestHelper h) { super.actualCureOverhealingQualifiesButOrdinaryHealingAndSelfGrantsDoNot(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:benevolence_refresh", maxTicks = 100) @Override
    public void sharedRestorationKeepsItsFirstOriginWhileEachActualGiverGetsCredit(GameTestHelper h) { super.sharedRestorationKeepsItsFirstOriginWhileEachActualGiverGetsCredit(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:benevolence_rift", maxTicks = 100) @Override
    public void actualHealingRiftRefreshesFromItsConfirmedContinuousHealing(GameTestHelper h) { super.actualHealingRiftRefreshesFromItsConfirmedContinuousHealing(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:benevolence_unknown", maxTicks = 100) @Override
    public void unknownNativeCureReceiptKeepsTheActualHealWithoutInventingFragmentCredit(GameTestHelper h) { super.unknownNativeCureReceiptKeepsTheActualHealWithoutInventingFragmentCredit(h); }
}
