package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FeedTheVoidGameTest extends com.imdomestic.chorus.test.FeedTheVoidGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:feed_paid", maxTicks = 20) @Override
    public void paidPhysicalShieldKillsActivateThenRewardExactlyOnceAndKeepGrenadeSelection(GameTestHelper h) throws Exception { super.paidPhysicalShieldKillsActivateThenRewardExactlyOnceAndKeepGrenadeSelection(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:feed_spike", maxTicks = 20) @Override
    public void paidStrandSpikeKillsQualifyOnlyForPrismaticAndThenUseSharedKillBenefits(GameTestHelper h) throws Exception { super.paidStrandSpikeKillsQualifyOnlyForPrismaticAndThenUseSharedKillBenefits(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:feed_shatter", maxTicks = 20) @Override
    public void actualFrozenEnemyShatterKillActivatesPrismaticDespiteHavingNoGenericAbilityTag(GameTestHelper h) throws Exception { super.actualFrozenEnemyShatterKillActivatesPrismaticDespiteHavingNoGenericAbilityTag(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:feed_volatile", maxTicks = 20) @Override
    public void actualWeaponAppliedVolatileExplosionActivatesOnlyPrismaticEvenWithLegacyAbilityCredit(GameTestHelper h) throws Exception { super.actualWeaponAppliedVolatileExplosionActivatesOnlyPrismaticEvenWithLegacyAbilityCredit(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:feed_equipment", maxTicks = 20) @Override
    public void AspectBindingAtImpactControlsActivationAndDuplicateSourcesDoNotDoubleActualHealing(GameTestHelper h) throws Exception { super.AspectBindingAtImpactControlsActivationAndDuplicateSourcesDoNotDoubleActualHealing(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:feed_detach", maxTicks = 20) @Override
    public void NativeWeaponKillsDoNotActivateButContinueOrdinaryDevourAfterAspectRemoval(GameTestHelper h) throws Exception { super.NativeWeaponKillsDoNotActivateButContinueOrdinaryDevourAfterAspectRemoval(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:feed_expiry", maxTicks = 230) @Override
    public void realTicksExpireTheInitialTenSecondsAndANewEligibleKillStartsANewActivation(GameTestHelper h) throws Exception { super.realTicksExpireTheInitialTenSecondsAndANewEligibleKillStartsANewActivation(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:feed_unknown", maxTicks = 20) @Override
    public void unknownHealingAfterActualSkillKillRetainsPaymentAndHealthWithoutReplay(GameTestHelper h) throws Exception { super.unknownHealingAfterActualSkillKillRetainsPaymentAndHealthWithoutReplay(h); }
}
