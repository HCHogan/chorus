package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ShotGroupTransferGameTest extends com.imdomestic.chorus.test.ShotGroupTransferGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:shot_native_transfer",maxTicks=80) @Override
    public void nativeTransferContinuesIssuerShotWithDestinationRayProjectileAndCurrentPerks(GameTestHelper h){
        super.nativeTransferContinuesIssuerShotWithDestinationRayProjectileAndCurrentPerks(h);
    }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:shot_native_restore",maxTicks=80) @Override
    public void jointArchiveRestoresBuffOwnedRemainingMembersWithoutReplayingActualHits(GameTestHelper h){
        super.jointArchiveRestoresBuffOwnedRemainingMembersWithoutReplayingActualHits(h);
    }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:shot_native_unknown",maxTicks=80) @Override
    public void lostDestinationDamageReceiptRetainsIssuerProgressAndCannotReplay(GameTestHelper h){
        super.lostDestinationDamageReceiptRetainsIssuerProgressAndCannotReplay(h);
    }
}
