package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class IncandescentGameTest extends com.imdomestic.chorus.test.IncandescentGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualKillsUseFourOrEightMetersExcludeAlliesAndApplyTheSelectedStacks(GameTestHelper h) throws Exception {super.actualKillsUseFourOrEightMetersExcludeAlliesAndApplyTheSelectedStacks(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:incandescent_cycle",maxTicks=40) @Override
    public void twoPhysicalWeaponKillsIgniteWithFirstSourceAndFurtherBurstStillDamagesDuringLockout(GameTestHelper h) throws Exception {super.twoPhysicalWeaponKillsIgniteWithFirstSourceAndFurtherBurstStillDamagesDuringLockout(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:incandescent_tick",maxTicks=40) @Override
    public void actualScorchRetainsWeaponBonusAndRankExemptionAfterEquipmentIsRemoved(GameTestHelper h) throws Exception {super.actualScorchRetainsWeaponBonusAndRankExemptionAfterEquipmentIsRemoved(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualUncreditedKillAndPerkRemovedInFlightDoNotExplode(GameTestHelper h) throws Exception {super.actualUncreditedKillAndPerkRemovedInFlightDoNotExplode(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void corpseRankChangesMovementAndRemovalCannotChangeConfirmedBlast(GameTestHelper h)throws Exception{super.corpseRankChangesMovementAndRemovalCannotChangeConfirmedBlast(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void derivedLethalBlastUsesSecondReceiptCenterAfterBothCorpsesAreRemoved(GameTestHelper h)throws Exception{super.derivedLethalBlastUsesSecondReceiptCenterAfterBothCorpsesAreRemoved(h);}
}
