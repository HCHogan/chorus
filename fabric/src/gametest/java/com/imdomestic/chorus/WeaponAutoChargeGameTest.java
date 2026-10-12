package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponAutoChargeGameTest extends com.imdomestic.chorus.test.WeaponAutoChargeGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:auto_charge_fire",maxTicks=40) @Override
    public void nativeFullBoundaryFiresWithoutReleaseAndReportsOneAcceptedShot(GameTestHelper h)throws Exception {super.nativeFullBoundaryFiresWithoutReleaseAndReportsOneAcceptedShot(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:auto_charge_release",maxTicks=30) @Override
    public void releaseAboveMinimumStillCancelsAndRecoveryRequiresANewPress(GameTestHelper h)throws Exception {super.releaseAboveMinimumStillCancelsAndRecoveryRequiresANewPress(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:auto_charge_lease",maxTicks=25) @Override
    public void expiredLeaseRejectsFullTimersEvenWhenRuntimeAdvancesBeforeNetworkMaintenance(GameTestHelper h)throws Exception {super.expiredLeaseRejectsFullTimersEvenWhenRuntimeAdvancesBeforeNetworkMaintenance(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:auto_charge_heartbeat",maxTicks=55) @Override
    public void validHeartbeatAuthorizesTheAcceptedFullDeadlineWithoutRestartingIt(GameTestHelper h)throws Exception {super.validHeartbeatAuthorizesTheAcceptedFullDeadlineWithoutRestartingIt(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:auto_charge_disconnect",maxTicks=25) @Override
    public void disconnectRevokesNetworkAndCommandAuthorityBeforeSettlingOverdueTimers(GameTestHelper h)throws Exception {super.disconnectRevokesNetworkAndCommandAuthorityBeforeSettlingOverdueTimers(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:auto_charge_quiesce",maxTicks=25) @Override
    public void quiescenceRevokesEveryHolderBeforeAnyRuntimeCanAutofire(GameTestHelper h)throws Exception {super.quiescenceRevokesEveryHolderBeforeAnyRuntimeCanAutofire(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:auto_charge_restore",maxTicks=25) @Override
    public void restoredDomainPlansHaveNoLiveInputAuthorityAtTheirFirstClockAdvance(GameTestHelper h)throws Exception {super.restoredDomainPlansHaveNoLiveInputAuthorityAtTheirFirstClockAdvance(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:auto_charge_unknown",maxTicks=25) @Override
    public void unknownAutomaticReactionKeepsPaymentAndCannotReplayFromHeldOrReleasePackets(GameTestHelper h)throws Exception {super.unknownAutomaticReactionKeepsPaymentAndCannotReplayFromHeldOrReleasePackets(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:auto_charge_stale_cancel",maxTicks=25) @Override
    public void staleCancellationCannotRevokeANewerCommandsAutomaticAuthority(GameTestHelper h)throws Exception {super.staleCancellationCannotRevokeANewerCommandsAutomaticAuthority(h);}
}
