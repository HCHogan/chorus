package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class StarvationGameTest extends com.imdomestic.chorus.test.StarvationGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:starvation_pickups", maxTicks = 20) @Override
    public void actualOrbAndBreachContactsGrantNormalOrEnhancedDevourAlongsideTheirBaseEnergy(GameTestHelper h) throws Exception { super.actualOrbAndBreachContactsGrantNormalOrEnhancedDevourAlongsideTheirBaseEnergy(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:starvation_full_super", maxTicks = 20) @Override
    public void fullSuperOrbRemainsUntilStarvationIsActuallyEquippedAndForeignCreatorGetsNoReward(GameTestHelper h) throws Exception { super.fullSuperOrbRemainsUntilStarvationIsActuallyEquippedAndForeignCreatorGetsNoReward(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:starvation_live", maxTicks = 20) @Override
    public void removingFragmentRevokesFullOrbPermissionWhileMissingSuperStillAllowsDevourWithFragment(GameTestHelper h) throws Exception { super.removingFragmentRevokesFullOrbPermissionWhileMissingSuperStillAllowsDevourWithFragment(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:starvation_class", maxTicks = 20) @Override
    public void privateBreachRetainsItsBaseRewardAfterProducerAndFragmentRemovalAndUsesCurrentClassAbility(GameTestHelper h) throws Exception { super.privateBreachRetainsItsBaseRewardAfterProducerAndFragmentRemovalAndUsesCurrentClassAbility(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:starvation_breach_time", maxTicks = 530) @Override
    public void realBreachSpawnsShareFiveSecondCooldownAndUncollectedCopiesExpireAfterTwentyFiveSeconds(GameTestHelper h) throws Exception { super.realBreachSpawnsShareFiveSecondCooldownAndUncollectedCopiesExpireAfterTwentyFiveSeconds(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:starvation_duration", maxTicks = 140) @Override
    public void fiveSecondDevourExpiresOnActualTicksWhileItsFragmentRemainsEquipped(GameTestHelper h) throws Exception { super.fiveSecondDevourExpiresOnActualTicksWhileItsFragmentRemainsEquipped(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:starvation_full_class", maxTicks = 20) @Override
    public void fullClassEnergyAndFullHealthDoNotPreventBreachCollectionOrFutureDevourKillRewards(GameTestHelper h) throws Exception { super.fullClassEnergyAndFullHealthDoNotPreventBreachCollectionOrFutureDevourKillRewards(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:starvation_unknown", maxTicks = 20) @Override
    public void unknownPickupHealingConsumesPhysicalUnitAndRetainsBaseEnergyAndHealthWithoutReplay(GameTestHelper h) throws Exception { super.unknownPickupHealingConsumesPhysicalUnitAndRetainsBaseEnergyAndHealthWithoutReplay(h); }
}
