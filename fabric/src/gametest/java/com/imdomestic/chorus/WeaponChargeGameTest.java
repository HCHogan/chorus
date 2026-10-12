package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponChargeGameTest extends com.imdomestic.chorus.test.WeaponChargeGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_partial",maxTicks=30) @Override
    public void partialReleasePaysOnceAndItsPhysicalArrowKeepsBothDamageComponents(GameTestHelper h)throws Exception {super.partialReleasePaysOnceAndItsPhysicalArrowKeepsBothDamageComponents(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_full",maxTicks=35) @Override
    public void fullChargeWaitsForReleaseAndContentReadsThePerfectDrawWindow(GameTestHelper h)throws Exception {super.fullChargeWaitsForReleaseAndContentReadsThePerfectDrawWindow(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_snapshot",maxTicks=30) @Override
    public void acceptedTimingSurvivesBuffRemovalAndStaleReleaseCannotConsumeTheCurrentPress(GameTestHelper h)throws Exception {super.acceptedTimingSurvivesBuffRemovalAndStaleReleaseCannotConsumeTheCurrentPress(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_restricted",maxTicks=30) @Override
    public void OngoingNativeRestrictionCancelsBothChargingAndReadyPhases(GameTestHelper h)throws Exception {super.OngoingNativeRestrictionCancelsBothChargingAndReadyPhases(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_lifecycle",maxTicks=30) @Override
    public void physicalUnequipSpectatorAndAcceptedReloadEndTheCharge(GameTestHelper h)throws Exception {super.physicalUnequipSpectatorAndAcceptedReloadEndTheCharge(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_life",maxTicks=30) @Override
    public void nativeRespawnWithTheSameUuidAndTransferredGearCannotInheritTheOldCharge(GameTestHelper h)throws Exception {super.nativeRespawnWithTheSameUuidAndTransferredGearCannotInheritTheOldCharge(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_unknown",maxTicks=30) @Override
    public void unknownPhysicalDamageAfterReleaseKeepsPaymentAndDoesNotReplayTheArrow(GameTestHelper h)throws Exception {super.unknownPhysicalDamageAfterReleaseKeepsPaymentAndDoesNotReplayTheArrow(h);}
}
