package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProjectileDestinationLifeGameTest extends com.imdomestic.chorus.test.ProjectileDestinationLifeGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:destination_life_move", maxTicks = 60) @Override
    public void sameLifeMovementKeepsTrackingAndArrivalAfterSourceRemoval(GameTestHelper h) throws Exception { super.sameLifeMovementKeepsTrackingAndArrivalAfterSourceRemoval(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:destination_life_replace", maxTicks = 60) @Override
    public void sameUuidAndNumericIdReplacementCannotInheritTheLockedFlight(GameTestHelper h) throws Exception { super.sameUuidAndNumericIdReplacementCannotInheritTheLockedFlight(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:destination_life_reload", maxTicks = 60) @Override
    public void nativeSaveReloadOfTheSameLifeRemainsAValidDestination(GameTestHelper h) throws Exception { super.nativeSaveReloadOfTheSameLifeRemainsAValidDestination(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:destination_life_missing", maxTicks = 60) @Override
    public void removedOrDeadDestinationEndsWithoutAnArrivalReward(GameTestHelper h) throws Exception { super.removedOrDeadDestinationEndsWithoutAnArrivalReward(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:destination_life_respawn", maxTicks = 60) @Override
    public void actualNewPlayerLifeCannotCatchBeforeTheNextProjectileTick(GameTestHelper h) throws Exception { super.actualNewPlayerLifeCannotCatchBeforeTheNextProjectileTick(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:destination_life_preserve", maxTicks = 60) @Override
    public void keepAllPlayerReplacementPreservesCatchEligibility(GameTestHelper h) throws Exception { super.keepAllPlayerReplacementPreservesCatchEligibility(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:destination_life_legacy", maxTicks = 60) @Override
    public void omittedReferencePreservesExplicitIdentityOnlyLegacyBehavior(GameTestHelper h) throws Exception { super.omittedReferencePreservesExplicitIdentityOnlyLegacyBehavior(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:destination_life_unknown", maxTicks = 60) @Override
    public void unknownArrivalRewardRetainsNativeWriteWithoutRepeatingTheLockedFlight(GameTestHelper h) throws Exception { super.unknownArrivalRewardRetainsNativeWriteWithoutRepeatingTheLockedFlight(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:destination_life_dimension", maxTicks = 60) @Override
    public void foreignDimensionReferenceCannotMatchEvenTheSameUuidAndLife(GameTestHelper h) throws Exception { super.foreignDimensionReferenceCannotMatchEvenTheSameUuidAndLife(h); }
}
