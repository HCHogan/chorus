package com.imdomestic.chorus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
public class RadiantGameTest extends com.imdomestic.chorus.test.RadiantGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualProjectileObservesChampionAtImpactAndWellOverridesItsThirtyPercent(GameTestHelper h)throws Exception{super.actualProjectileObservesChampionAtImpactAndWellOverridesItsThirtyPercent(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:radiant_expiry",maxTicks=240) @Override
    public void tenSecondExpiryDoesNotRewriteAnEarlierShotAndNewAttacksHaveNoExpiredBonus(GameTestHelper h)throws Exception{super.tenSecondExpiryDoesNotRewriteAnEarlierShotAndNewAttacksHaveNoExpiredBonus(h);}
}
