package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class UnflinchingGameTest extends com.imdomestic.chorus.test.UnflinchingGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:unflinching_tiers", maxTicks=20) @Override
    public void threePhysicalChestSocketsSelectTiersWithoutMultiplyingEachCopy(GameTestHelper h) throws Exception { super.threePhysicalChestSocketsSelectTiersWithoutMultiplyingEachCopy(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:unflinching_switch", maxTicks=55) @Override
    public void mixedElementSocketsFollowCurrentSolarOrArcWeaponAndClearTheOldPerk(GameTestHelper h) throws Exception { super.mixedElementSocketsFollowCurrentSolarOrArcWeaponAndClearTheOldPerk(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:unflinching_fire", maxTicks=40) @Override
    public void actualWeaponFireKeepsArmorAndActiveNoDistractionsAsIndependentFactors(GameTestHelper h) throws Exception { super.actualWeaponFireKeepsArmorAndActiveNoDistractionsAsIndependentFactors(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:unflinching_ads", maxTicks=20) @Override
    public void absorptionLossUsesCompletedAdsAndReleaseRemovesTheArmorBenefit(GameTestHelper h) throws Exception { super.absorptionLossUsesCompletedAdsAndReleaseRemovesTheArmorBenefit(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:unflinching_ownership", maxTicks=40) @Override
    public void foreignModSourcesDoNotCountAndReplacingArmorPreservesTheActiveWeaponPerk(GameTestHelper h) throws Exception { super.foreignModSourcesDoNotCountAndReplacingArmorPreservesTheActiveWeaponPerk(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:unflinching_unknown", maxTicks=40) @Override
    public void unknownComposedImpulseRetainsActualDamageAndRotationWithoutReplay(GameTestHelper h) throws Exception { super.unknownComposedImpulseRetainsActualDamageAndRotationWithoutReplay(h); }
}
