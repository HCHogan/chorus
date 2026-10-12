package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponRepeatChargeGameTest extends com.imdomestic.chorus.test.WeaponRepeatChargeGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_cycles",maxTicks=80) @Override
    public void oneNetworkPressDrivesTwoWholeSalvosAndReleaseStopsTheRecovery(GameTestHelper h)throws Exception{super.oneNetworkPressDrivesTwoWholeSalvosAndReleaseStopsTheRecovery(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_perks",maxTicks=55) @Override
    public void firstSalvoKillChangesTheNextAutomaticCyclesAcceptedDuration(GameTestHelper h)throws Exception{super.firstSalvoKillChangesTheNextAutomaticCyclesAcceptedDuration(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_lease",maxTicks=30) @Override
    public void expiredLeaseCannotReplayMultipleHistoricalCyclesDuringClockCatchup(GameTestHelper h)throws Exception{super.expiredLeaseCannotReplayMultipleHistoricalCyclesDuringClockCatchup(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_stow",maxTicks=55) @Override
    public void stowAndImmediateRedrawRetireRecoveryBeforeTheNextNetworkTick(GameTestHelper h)throws Exception{super.stowAndImmediateRedrawRetireRecoveryBeforeTheNextNetworkTick(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_restore",maxTicks=30) @Override
    public void restoredRecoveryHasNoLiveAuthorityToStartAnotherCycle(GameTestHelper h)throws Exception{super.restoredRecoveryHasNoLiveAuthorityToStartAnotherCycle(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_respawn",maxTicks=55) @Override
    public void nativeRespawnCannotTransferTheOldRecoveryToTheReplacementPlayer(GameTestHelper h)throws Exception{super.nativeRespawnCannotTransferTheOldRecoveryToTheReplacementPlayer(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_unknown",maxTicks=30) @Override
    public void unknownPhysicalDamageStopsTheRemainingCycleWithoutRepeatingTheWorldWrite(GameTestHelper h)throws Exception{super.unknownPhysicalDamageStopsTheRemainingCycleWithoutRepeatingTheWorldWrite(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_disconnect",maxTicks=30) @Override
    public void disconnectRevokesRecoveryBeforeSettlingOverdueRepeatTimers(GameTestHelper h)throws Exception{super.disconnectRevokesRecoveryBeforeSettlingOverdueRepeatTimers(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_stop",maxTicks=30) @Override
    public void cleanStopRevokesCommandRecoveryBeforeItsNextAutomaticCycle(GameTestHelper h)throws Exception{super.cleanStopRevokesCommandRecoveryBeforeItsNextAutomaticCycle(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_release_catchup",maxTicks=30) @Override
    public void matchedReleaseRevokesAnUnexpiredLeaseBeforeHistoricalCyclesAdvance(GameTestHelper h)throws Exception{super.matchedReleaseRevokesAnUnexpiredLeaseBeforeHistoricalCyclesAdvance(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_cancel_catchup",maxTicks=30) @Override
    public void matchedCancelRevokesAnUnexpiredLeaseBeforeHistoricalCyclesAdvance(GameTestHelper h)throws Exception{super.matchedCancelRevokesAnUnexpiredLeaseBeforeHistoricalCyclesAdvance(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_command_release",maxTicks=30) @Override
    public void commandReleaseUsesTheSamePreAdvanceCancellationBoundary(GameTestHelper h)throws Exception{super.commandReleaseUsesTheSamePreAdvanceCancellationBoundary(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:repeat_charge_command_cancel",maxTicks=30) @Override
    public void commandCancelUsesTheSamePreAdvanceCancellationBoundary(GameTestHelper h)throws Exception{super.commandCancelUsesTheSamePreAdvanceCancellationBoundary(h);}
}
