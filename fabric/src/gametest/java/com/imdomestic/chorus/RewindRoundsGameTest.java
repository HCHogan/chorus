package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class RewindRoundsGameTest extends com.imdomestic.chorus.test.RewindRoundsGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rewind_variants", maxTicks = 85) @Override
    public void physicalHitsRefillBothVariantsFromTheirOwnReservesWithoutReloading(GameTestHelper h) throws Exception { super.physicalHitsRefillBothVariantsFromTheirOwnReservesWithoutReloading(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rewind_miss", maxTicks = 60) @Override
    public void finalPhysicalMissStillRefillsTheStowedWeaponUsingEarlierHits(GameTestHelper h) throws Exception { super.finalPhysicalMissStillRefillsTheStowedWeaponUsingEarlierHits(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rewind_bolts", maxTicks = 100) @Override
    public void adaptiveBurstUsesEveryActualBoltAndExplicitEnhancedCalibration(GameTestHelper h) throws Exception { super.adaptiveBurstUsesEveryActualBoltAndExplicitEnhancedCalibration(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rewind_groups", maxTicks = 60) @Override
    public void firstCompletedGroupCannotPrematurelySettleTheEmptyingTrigger(GameTestHelper h) throws Exception { super.firstCompletedGroupCannotPrematurelySettleTheEmptyingTrigger(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rewind_reload", maxTicks = 70) @Override
    public void actualManualReloadInvalidatesOutstandingEmptyingFire(GameTestHelper h) throws Exception { super.actualManualReloadInvalidatesOutstandingEmptyingFire(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rewind_replace", maxTicks = 60) @Override
    public void realPerkReplacementClearsPendingIdentityBeforeOldProjectileHits(GameTestHelper h) throws Exception { super.realPerkReplacementClearsPendingIdentityBeforeOldProjectileHits(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rewind_unknown", maxTicks = 60) @Override
    public void unknownReactionKeepsTheLimitedReserveTransferWithoutReplaying(GameTestHelper h) throws Exception { super.unknownReactionKeepsTheLimitedReserveTransferWithoutReplaying(h); }
}
