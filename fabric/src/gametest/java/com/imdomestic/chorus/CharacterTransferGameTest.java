package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class CharacterTransferGameTest extends com.imdomestic.chorus.test.CharacterTransferGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void realPlayerContainersAndSourceOwnedHealingSurviveJointCharacterHandoffAndRestore(GameTestHelper h){super.realPlayerContainersAndSourceOwnedHealingSurviveJointCharacterHandoffAndRestore(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void transferredReloadUsesRemainingTimeAndActualContainerWithoutReinitializingAmmunition(GameTestHelper h){super.transferredReloadUsesRemainingTimeAndActualContainerWithoutReinitializingAmmunition(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void unknownSourceHealingRetainsTransferredCharacterNativeHealthAndOriginalRefundClaim(GameTestHelper h){super.unknownSourceHealingRetainsTransferredCharacterNativeHealthAndOriginalRefundClaim(h);}
}
