package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AimImpulseGameTest extends com.imdomestic.chorus.test.AimImpulseGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:aim_impulse_player", maxTicks=12) @Override
    public void paidRuleSamplesResistanceThenRotatesWithoutMovingOrChangingVelocity(GameTestHelper h) { super.paidRuleSamplesResistanceThenRotatesWithoutMovingOrChangingVelocity(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:aim_impulse_limits") @Override
    public void nativePitchLimitsAndFloatRoundingProduceActualDeltasAndNoZeroPackets(GameTestHelper h) { super.nativePitchLimitsAndFloatRoundingProduceActualDeltasAndNoZeroPackets(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:aim_impulse_ray") @Override
    public void realAimChangeRedirectsFollowingHitscanAndActualDamage(GameTestHelper h) throws Exception { super.realAimChangeRedirectsFollowingHitscanAndActualDamage(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:aim_impulse_eligibility") @Override
    public void missingDeadSpectatorSleepingAndUnloadedRecipientsAreRejected(GameTestHelper h) { super.missingDeadSpectatorSleepingAndUnloadedRecipientsAreRejected(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:aim_impulse_unknown", maxTicks=12) @Override
    public void unknownRotationPreservesPaymentAndNeverResendsOrRunsCompletion(GameTestHelper h) { super.unknownRotationPreservesPaymentAndNeverResendsOrRunsCompletion(h); }
}
