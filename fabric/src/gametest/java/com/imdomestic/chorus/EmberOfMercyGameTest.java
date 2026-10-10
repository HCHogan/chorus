package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EmberOfMercyGameTest extends com.imdomestic.chorus.test.EmberOfMercyGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:mercy_healing",maxTicks=90) @Override
    public void physicalCollectionUsesCurrentSolaceAndRecoverySurvivesDamageAndFragmentRemoval(GameTestHelper h)throws Exception{super.physicalCollectionUsesCurrentSolaceAndRecoverySurvivesDamageAndFragmentRemoval(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void physicalPickupCapsLongRestorationWithoutDowngradingOrErasingItsHistory(GameTestHelper h)throws Exception{super.physicalPickupCapsLongRestorationWithoutDowngradingOrErasingItsHistory(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void restorationUsesRecipientsSolaceRatherThanTheApplyingPlayersFragment(GameTestHelper h)throws Exception{super.restorationUsesRecipientsSolaceRatherThanTheApplyingPlayersFragment(h);}
}
