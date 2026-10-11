package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class TimelostMagazineGameTest extends com.imdomestic.chorus.test.TimelostMagazineGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:timelost_lifecycle", maxTicks = 450) @Override
    public void paidSuperEndsOnceAndStowedNormalEnhancedWindowsExpireWithoutDeletingLoadedRounds(GameTestHelper h) throws Exception { super.paidSuperEndsOnceAndStowedNormalEnhancedWindowsExpireWithoutDeletingLoadedRounds(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:timelost_shells", maxTicks = 55) @Override
    public void foundVerdictUsesTwoActualShellTransfersAfterFourPaidShots(GameTestHelper h) throws Exception { super.foundVerdictUsesTwoActualShellTransfersAfterFourPaidShots(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:timelost_energy", maxTicks = 45) @Override
    public void physicalWeaponKillsCreditTheSelectedSuperAtImpactUsingOnlyTheirOwnPerk(GameTestHelper h) throws Exception { super.physicalWeaponKillsCreditTheSelectedSuperAtImpactUsingOnlyTheirOwnPerk(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:timelost_overflow", maxTicks = 45) @Override
    public void realAmmoBrickUsesExpandedBaseForEnhancedOverflowAndConservesReserves(GameTestHelper h) throws Exception { super.realAmmoBrickUsesExpandedBaseForEnhancedOverflowAndConservesReserves(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:timelost_detach", maxTicks = 40) @Override
    public void replacingPerkBeforeExpiryPreventsActivationAndLaterDetachPreservesLoadedAmmo(GameTestHelper h) throws Exception { super.replacingPerkBeforeExpiryPreventsActivationAndLaterDetachPreservesLoadedAmmo(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:timelost_cancel", maxTicks = 40) @Override
    public void removingSyntheticSuperStateDoesNotForgeAnExpiryReward(GameTestHelper h) throws Exception { super.removingSyntheticSuperStateDoesNotForgeAnExpiryReward(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:timelost_unknown", maxTicks = 40) @Override
    public void unknownWorldReactionAfterRefillDoesNotDuplicateTheCommittedTransfer(GameTestHelper h) throws Exception { super.unknownWorldReactionAfterRefillDoesNotDuplicateTheCommittedTransfer(h); }
}
