package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class WeaponChargeInputGameTest extends com.imdomestic.chorus.test.WeaponChargeInputGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_input_packets",maxTicks=25) @Override
    public void pairedPacketsUseServerTokensAndCannotReleaseAnotherGestureOrDimension(GameTestHelper h)throws Exception {super.pairedPacketsUseServerTokensAndCannotReleaseAnotherGestureOrDimension(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_input_progress",maxTicks=65) @Override
    public void heartbeatsPreserveTimingAndProgressIsAnAuthoritativeServerSample(GameTestHelper h)throws Exception {super.heartbeatsPreserveTimingAndProgressIsAnAuthoritativeServerSample(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_input_lease",maxTicks=55) @Override
    public void exactLeaseExpiryRejectsLateHeartbeatAndReleaseWithoutRefiring(GameTestHelper h)throws Exception {super.exactLeaseExpiryRejectsLateHeartbeatAndReleaseWithoutRefiring(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_input_gate",maxTicks=25) @Override
    public void restrictionsRetireTheGestureAndRecoveryNeedsAFreshPress(GameTestHelper h)throws Exception {super.restrictionsRetireTheGestureAndRecoveryNeedsAFreshPress(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_input_reload",maxTicks=30) @Override
    public void acceptedReloadAndUnequipDoNotRestartChargingFromHeartbeat(GameTestHelper h)throws Exception {super.acceptedReloadAndUnequipDoNotRestartChargingFromHeartbeat(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_input_runtime",maxTicks=25) @Override
    public void runtimeRestoreKeepsDomainDataButDoesNotTransferTheLiveGesture(GameTestHelper h)throws Exception {super.runtimeRestoreKeepsDomainDataButDoesNotTransferTheLiveGesture(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_input_life",maxTicks=25) @Override
    public void nativeRespawnRetiresTheOldConnectionGestureAndDelayedRelease(GameTestHelper h)throws Exception {super.nativeRespawnRetiresTheOldConnectionGestureAndDelayedRelease(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_input_unknown",maxTicks=25) @Override
    public void unknownReleaseRetiresSequenceAndNeverRetriesTheAcceptedPayment(GameTestHelper h)throws Exception {super.unknownReleaseRetiresSequenceAndNeverRetriesTheAcceptedPayment(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:charge_input_cleanup",maxTicks=25) @Override
    public void disconnectCleansCommandPlansAndQuiescenceRemovesNetworkPlansBeforeSave(GameTestHelper h)throws Exception {super.disconnectCleansCommandPlansAndQuiescenceRemovesNetworkPlansBeforeSave(h);}
}
