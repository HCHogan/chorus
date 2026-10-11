package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeakenGameTest extends com.imdomestic.chorus.test.WeakenGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weaken_recipients", maxTicks = 145) @Override
    public void actualPlayerAndCombatantUseDifferentStrengthAndExpiryEvenInTheSameWorld(GameTestHelper h) { super.actualPlayerAndCombatantUseDifferentStrengthAndExpiryEvenInTheSameWorld(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weaken_groups", maxTicks = 20) @Override
    public void realDamageTakesTheStrongestGlobalDebuffThenIndependentResistance(GameTestHelper h) { super.realDamageTakesTheStrongestGlobalDebuffThenIndependentResistance(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weaken_refresh", maxTicks = 155) @Override
    public void ordinaryReapplicationExtendsRefreshableStrongWeakenWithoutDowngradingItsDamage(GameTestHelper h) { super.ordinaryReapplicationExtendsRefreshableStrongWeakenWithoutDowngradingItsDamage(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weaken_rejection", maxTicks = 20) @Override
    public void deniedOrUnavailableRecipientsDoNotAcquireOrRefreshEitherPenalty(GameTestHelper h) { super.deniedOrUnavailableRecipientsDoNotAcquireOrRefreshEitherPenalty(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:weaken_death", maxTicks = 20) @Override
    public void confirmedDeathCleansLiveStatusWhileTheKillKeepsHistoricalWeakenIdentity(GameTestHelper h) { super.confirmedDeathCleansLiveStatusWhileTheKillKeepsHistoricalWeakenIdentity(h); }
}
