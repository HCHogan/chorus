package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SuppressionNativeGameTest extends com.imdomestic.chorus.test.SuppressionNativeGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:suppression_native_tiers") @Override
    public void nativeSuppressionUsesExplicitCurrentTierAndDoesNotDisableBossOrChampionShooting(GameTestHelper h){super.nativeSuppressionUsesExplicitCurrentTierAndDoesNotDisableBossOrChampionShooting(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:suppression_native_expiry",maxTicks=222) @Override
    public void tenSecondPveSuppressionExpiresOnTheWorldClockAndRestoresNativeFire(GameTestHelper h){super.tenSecondPveSuppressionExpiresOnTheWorldClockAndRestoresNativeFire(h);}
}
