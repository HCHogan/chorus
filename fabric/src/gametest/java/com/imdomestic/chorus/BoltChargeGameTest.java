package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class BoltChargeGameTest extends com.imdomestic.chorus.test.BoltChargeGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualWeaponDamageArmsTheNextHitAndSharedComponentsGrantOnlyOneStack(GameTestHelper h) throws Exception { super.actualWeaponDamageArmsTheNextHitAndSharedComponentsGrantOnlyOneStack(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void overflowGainsUseCreditedStacksRatherThanTheCappedStoredDifference(GameTestHelper h) throws Exception { super.overflowGainsUseCreditedStacksRatherThanTheCappedStoredDifference(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:bolt_pve", maxTicks = 25) @Override
    public void pveReadyChargeDischargesOnceAfterHalfASecondWithExplicitProcExclusions(GameTestHelper h) throws Exception { super.pveReadyChargeDischargesOnceAfterHalfASecondWithExplicitProcExclusions(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:bolt_pvp", maxTicks = 25) @Override
    public void pvpReadyChargeUsesBothGuardianCenterDamageComponents(GameTestHelper h) throws Exception { super.pvpReadyChargeUsesBothGuardianCenterDamageComponents(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:bolt_unknown", maxTicks = 25) @Override
    public void failureAfterRealBoltDamageKeepsSpentChargeAndDoesNotReplayTheHit(GameTestHelper h) throws Exception { super.failureAfterRealBoltDamageKeepsSpentChargeAndDoesNotReplayTheHit(h); }
}
