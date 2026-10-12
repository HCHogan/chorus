package com.imdomestic.chorus;

import net.minecraft.gametest.framework.GameTestHelper;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

public class ControlledBurstGameTest extends com.imdomestic.chorus.test.ControlledBurstGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:controlled_burst_normal",maxTicks=60) @Override
    public void normalFullBurstHitsGrantDamageAndChargeTimeToTheNextNativeShot(GameTestHelper h)throws Exception{super.normalFullBurstHitsGrantDamageAndChargeTimeToTheNextNativeShot(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:controlled_burst_enhanced",maxTicks=60) @Override
    public void enhancedFullBurstUsesItsLongerDurationWithTheSameMultipliers(GameTestHelper h)throws Exception{super.enhancedFullBurstUsesItsLongerDurationWithTheSameMultipliers(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:controlled_burst_kill",maxTicks=35) @Override
    public void earlyNativeKillChangesLaterBoltDamageAndCombinesWithWarmup(GameTestHelper h)throws Exception{super.earlyNativeKillChangesLaterBoltDamageAndCombinesWithWarmup(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:controlled_burst_stow",maxTicks=75) @Override
    public void stowingRetiresUnlaunchedMembersButAnAlreadyFlyingKillKeepsItsCredit(GameTestHelper h)throws Exception{super.stowingRetiresUnlaunchedMembersButAnAlreadyFlyingKillKeepsItsCredit(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:controlled_burst_geometry",maxTicks=30) @Override
    public void eachNativeBoltReadsCurrentMuzzleWhileRespawnCannotContinueTheOldLife(GameTestHelper h)throws Exception{super.eachNativeBoltReadsCurrentMuzzleWhileRespawnCannotContinueTheOldLife(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:controlled_burst_unknown",maxTicks=30) @Override
    public void unknownNativeHitRetainsAppliedDamageAndCannotReplayTheSalvo(GameTestHelper h)throws Exception{super.unknownNativeHitRetainsAppliedDamageAndCannotReplayTheSalvo(h);}
}
