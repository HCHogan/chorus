package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AscensionGameTest extends com.imdomestic.chorus.test.AscensionGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void paidVerticalCastUsesCapturedTenMeterOriginAndSeparatesAlliesFromEnemies(GameTestHelper h) throws Exception { super.paidVerticalCastUsesCapturedTenMeterOriginAndSeparatesAlliesFromEnemies(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void solidCeilingClipsAscentWhileStatusDenialOnlyRejectsThatTargetsJolt(GameTestHelper h) throws Exception { super.solidCeilingClipsAscentWhileStatusDenialOnlyRejectsThatTargetsJolt(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void groundedMissingSelectionAndExhaustedChargeProduceNoAdditionalWorldEffects(GameTestHelper h) throws Exception { super.groundedMissingSelectionAndExhaustedChargeProduceNoAdditionalWorldEffects(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:ascension_reload", maxTicks=16) @Override
    public void selectedMarksmanBenefitReloadsActualWeaponsAfterSelectionChangesWithoutAnotherCast(GameTestHelper h) throws Exception { super.selectedMarksmanBenefitReloadsActualWeaponsAfterSelectionChangesWithoutAnotherCast(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:ascension_return", maxTicks=16) @Override
    public void selectedGamblerBenefitReturnsTwoChargesWithoutReplayingGroundedDodge(GameTestHelper h) throws Exception { super.selectedGamblerBenefitReturnsTwoChargesWithoutReplayingGroundedDodge(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownRelocationRetainsActualMovementAndPaymentAndCannotReplay(GameTestHelper h) throws Exception { super.unknownRelocationRetainsActualMovementAndPaymentAndCannotReplay(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualAscensionTriggersEquippedKickstartThenAttractionRefillsOnlyTheLiveChargeGap(GameTestHelper h) throws Exception { super.actualAscensionTriggersEquippedKickstartThenAttractionRefillsOnlyTheLiveChargeGap(h); }
}
