package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FrenzyGameTest extends com.imdomestic.chorus.test.FrenzyGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeEnvironmentalDamageWithoutAnActorDoesNotStartCombat(GameTestHelper h) throws Exception { super.nativeEnvironmentalDamageWithoutAnActorDoesNotStartCombat(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:frenzy_combat", maxTicks = 535) @Override
    public void nonlethalCombatActivatesStowedWeaponAndReloadKeepsAcceptedDurationAfterBuffExpiry(GameTestHelper h) throws Exception { super.nonlethalCombatActivatesStowedWeaponAndReloadKeepsAcceptedDurationAfterBuffExpiry(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:frenzy_gap", maxTicks = 365) @Override
    public void actualGapResetsNormalProgressWhileEnhancedWindowContinuesAndRefreshUsesCalibration(GameTestHelper h) throws Exception { super.actualGapResetsNormalProgressWhileEnhancedWindowContinuesAndRefreshUsesCalibration(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:frenzy_detach", maxTicks = 415) @Override
    public void returningAnActualItemAndReequippingItCannotInheritItsCancelledWindup(GameTestHelper h) throws Exception { super.returningAnActualItemAndReequippingItCannotInheritItsCancelledWindup(h); }
}
