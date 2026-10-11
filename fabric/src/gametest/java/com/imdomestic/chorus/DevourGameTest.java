package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DevourGameTest extends com.imdomestic.chorus.test.DevourGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:devour_tiers", maxTicks = 20) @Override
    public void actualTieredAndPlayerDeathsRestoreNormalOrEnhancedHealthAndGrenadeEnergy(GameTestHelper h) throws Exception { super.actualTieredAndPlayerDeathsRestoreNormalOrEnhancedHealthAndGrenadeEnergy(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:devour_payment", maxTicks = 20) @Override
    public void nativeDeathsFillCurrentGrenadeAccountThenRealPlayerInputSpendsExactlyOneCharge(GameTestHelper h) throws Exception { super.nativeDeathsFillCurrentGrenadeAccountThenRealPlayerInputSpendsExactlyOneCharge(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:devour_extension", maxTicks = 330) @Override
    public void normalKillExtendsByFiveSecondsAndActualTicksEventuallyExpireTheSharedState(GameTestHelper h) throws Exception { super.normalKillExtendsByFiveSecondsAndActualTicksEventuallyExpireTheSharedState(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:devour_persistence", maxTicks = 490) @Override
    public void quotedPersistenceKillExtensionAddsEightSecondsWithoutMultiplyingItAgain(GameTestHelper h) throws Exception { super.quotedPersistenceKillExtensionAddsEightSecondsWithoutMultiplyingItAgain(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:devour_singularity", maxTicks = 20) @Override
    public void actualDevourAcquiredInFlightQualifiesSingularityAndTheFollowingKillPaysBothEffects(GameTestHelper h) throws Exception { super.actualDevourAcquiredInFlightQualifiesSingularityAndTheFollowingKillPaysBothEffects(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:devour_history", maxTicks = 20) @Override
    public void corpseRemovalKeepsObservedTierWhileUnknownTierRetainsOnlyKnownBenefits(GameTestHelper h) throws Exception { super.corpseRemovalKeepsObservedTierWhileUnknownTierRetainsOnlyKnownBenefits(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:devour_lifecycle", maxTicks = 20) @Override
    public void foreignGrantorDetachRetainsRecipientBenefitsAndActualDeathCleansTheState(GameTestHelper h) throws Exception { super.foreignGrantorDetachRetainsRecipientBenefitsAndActualDeathCleansTheState(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:devour_unknown", maxTicks = 20) @Override
    public void unknownHealKeepsActualHealthWriteWithoutGrantingLaterEnergyOrReplayingTheKill(GameTestHelper h) throws Exception { super.unknownHealKeepsActualHealthWriteWithoutGrantingLaterEnergyOrReplayingTheKill(h); }
}
