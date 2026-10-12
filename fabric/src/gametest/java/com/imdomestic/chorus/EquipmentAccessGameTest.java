package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EquipmentAccessGameTest extends com.imdomestic.chorus.test.EquipmentAccessGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void clientRequestsCannotGrantAccessAndOldViewsBecomeStaleWhenAnOperatorGrantsIt(GameTestHelper h) throws Exception { super.clientRequestsCannotGrantAccessAndOldViewsBecomeStaleWhenAnOperatorGrantsIt(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void revokeAndRegrantReconcileSourcesWithoutMovingItemsOrRepeatingANoopGrant(GameTestHelper h) throws Exception { super.revokeAndRegrantReconcileSourcesWithoutMovingItemsOrRepeatingANoopGrant(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void grantsSurvivePlayerSaveButDoNotTravelWithAGiftedItem(GameTestHelper h) throws Exception { super.grantsSurvivePlayerSaveButDoNotTravelWithAGiftedItem(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void grantsRemainThroughDeathAndMoveToTheReplacementPlayerOnlyOnce(GameTestHelper h) throws Exception { super.grantsRemainThroughDeathAndMoveToTheReplacementPlayerOnlyOnce(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:unflinching_access", maxTicks=25) @Override
    public void artifactDiscountHasTheSameRealFlinchAndRevocationRemovesItsPhysicalBenefit(GameTestHelper h) throws Exception { super.artifactDiscountHasTheSameRealFlinchAndRevocationRemovesItsPhysicalBenefit(h); }
}
