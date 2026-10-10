package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class JoltGameTest extends com.imdomestic.chorus.test.JoltGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualJoltChainsDenyBoltDischargeButKeepOtherHitReactions(GameTestHelper h) throws Exception { super.actualJoltChainsDenyBoltDischargeButKeepOtherHitReactions(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeApplyingHitCountsAndAnotherAttackerOwnsModeSpecificChainDamage(GameTestHelper h) throws Exception { super.nativeApplyingHitCountsAndAnotherAttackerOwnsModeSpecificChainDamage(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void playerCenterRequiresActualOtherPlayerLossIncludingAbsorptionAndRejectsCancelledChains(GameTestHelper h) throws Exception { super.playerCenterRequiresActualOtherPlayerLossIncludingAbsorptionAndRejectsCancelledChains(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void fatalThresholdStillChainsBeforeCleanupAndFreshAfterDamageApplicationDoesNotCount(GameTestHelper h) throws Exception { super.fatalThresholdStillChainsBeforeCleanupAndFreshAfterDamageApplicationDoesNotCount(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void adjacentJoltsMayCascadeWithinOneRootWithIndependentCooldowns(GameTestHelper h) throws Exception { super.adjacentJoltsMayCascadeWithinOneRootWithIndependentCooldowns(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:jolt_cooldown", maxTicks = 30) @Override
    public void nativeCooldownBlocksEarlyHitsAndAllowsAnotherActivationAtExactDeadline(GameTestHelper h) throws Exception { super.nativeCooldownBlocksEarlyHitsAndAllowsAnotherActivationAtExactDeadline(h); }
}
