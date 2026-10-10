package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ImpulseGameTest extends com.imdomestic.chorus.test.ImpulseGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:impulse_player",maxTicks=12) @Override
    public void ordinarySkillUsesItsCapturedHeadingAndSendsTheActualVelocityPacket(GameTestHelper h)throws Exception{super.ordinarySkillUsesItsCapturedHeadingAndSendsTheActualVelocityPacket(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:impulse_collision",maxTicks=18) @Override
    public void outwardImpulseMovesAMobWithVanillaCollisionInsteadOfTeleporting(GameTestHelper h){super.outwardImpulseMovesAMobWithVanillaCollisionInsteadOfTeleporting(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:impulse_eligibility") @Override
    public void rejectedRecipientsAndAbsentAxesNeverAcquireVelocityOrGracePackets(GameTestHelper h){super.rejectedRecipientsAndAbsentAxesNeverAcquireVelocityOrGracePackets(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:impulse_unknown",maxTicks=12) @Override
    public void unknownNativeImpulseKeepsVelocityAndPaymentWithoutResending(GameTestHelper h)throws Exception{super.unknownNativeImpulseKeepsVelocityAndPaymentWithoutResending(h);}
}
