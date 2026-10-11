package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ExplosivePayloadGameTest extends com.imdomestic.chorus.test.ExplosivePayloadGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:payload_split",maxTicks=30) @Override
    public void actualBodyAndPrecisionContactsHaveSeparateTravelAndBlastDamage(GameTestHelper h)throws Exception {super.actualBodyAndPrecisionContactsHaveSeparateTravelAndBlastDamage(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:payload_radial",maxTicks=30) @Override
    public void explosionExcludesPrimaryDuplicationOwnerAndAlliesAndUsesEachNeighborsDistance(GameTestHelper h)throws Exception {super.explosionExcludesPrimaryDuplicationOwnerAndAlliesAndUsesEachNeighborsDistance(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:payload_death",maxTicks=30) @Override
    public void lethalDirectStillExplodesAndBlastFinalBlowKeepsWeaponCreditWithoutPrecision(GameTestHelper h)throws Exception {super.lethalDirectStillExplodesAndBlastFinalBlowKeepsWeaponCreditWithoutPrecision(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:payload_wall",maxTicks=30) @Override
    public void blockImpactHasOnlySplashWhileExpiredProjectileDoesNotExplode(GameTestHelper h)throws Exception {super.blockImpactHasOnlySplashWhileExpiredProjectileDoesNotExplode(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:payload_snapshot",maxTicks=30) @Override
    public void acceptedBothComponentsKeepSourceBonusAfterUnequipAndOwnerMovement(GameTestHelper h)throws Exception {super.acceptedBothComponentsKeepSourceBonusAfterUnequipAndOwnerMovement(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:payload_shield",maxTicks=30) @Override
    public void shieldExcludesOnlyAdjustedPrecisionAndOneUseBuffCoversBothComponents(GameTestHelper h)throws Exception {super.shieldExcludesOnlyAdjustedPrecisionAndOneUseBuffCoversBothComponents(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:payload_unknown",maxTicks=30) @Override
    public void unknownSecondDamageRetainsBothAppliedLossesWithoutReplayingContact(GameTestHelper h)throws Exception {super.unknownSecondDamageRetainsBothAppliedLossesWithoutReplayingContact(h);}
}
