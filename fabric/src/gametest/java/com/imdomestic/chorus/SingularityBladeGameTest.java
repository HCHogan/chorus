package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SingularityBladeGameTest extends com.imdomestic.chorus.test.SingularityBladeGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:singularity_recipients", maxTicks = 145) @Override
    public void paidMeleeAndSwordHitsUseActualRecipientStrengthAndBothLastSixSeconds(GameTestHelper h) throws Exception { super.paidMeleeAndSwordHitsUseActualRecipientStrengthAndBothLastSixSeconds(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:singularity_impact", maxTicks = 20) @Override
    public void devourAndInvisibilityGainedOrLostDuringFlightDetermineActualHitEligibility(GameTestHelper h) throws Exception { super.devourAndInvisibilityGainedOrLostDuringFlightDetermineActualHitEligibility(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:singularity_burst", maxTicks = 20) @Override
    public void paidMeleeAndSwordKillsApplyFourMeterBurstWithTeamAndPerTargetStatusChecks(GameTestHelper h) throws Exception { super.paidMeleeAndSwordKillsApplyFourMeterBurstWithTeamAndPerTargetStatusChecks(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:singularity_reactions", maxTicks = 20) @Override
    public void sameHitBuffReactionsCannotRewriteTheQualificationOfItsFollowingKill(GameTestHelper h) throws Exception { super.sameHitBuffReactionsCannotRewriteTheQualificationOfItsFollowingKill(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:singularity_history", maxTicks = 20) @Override
    public void corpseRemovalBeforeFactDeliveryStillUsesTheActualHistoricalDeathCenter(GameTestHelper h) throws Exception { super.corpseRemovalBeforeFactDeliveryStillUsesTheActualHistoricalDeathCenter(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:singularity_detach", maxTicks = 40) @Override
    public void weaponStowRetainsEligibilityWhileArtifactDetachPreventsFutureHitsAndKeepsOldWeaken(GameTestHelper h) throws Exception { super.weaponStowRetainsEligibilityWhileArtifactDetachPreventsFutureHitsAndKeepsOldWeaken(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:singularity_unknown", maxTicks = 20) @Override
    public void unknownPhysicalDamageKeepsHealthLossWithoutInventingWeakenOrReplayingTheAttack(GameTestHelper h) throws Exception { super.unknownPhysicalDamageKeepsHealthLossWithoutInventingWeakenOrReplayingTheAttack(h); }
}
