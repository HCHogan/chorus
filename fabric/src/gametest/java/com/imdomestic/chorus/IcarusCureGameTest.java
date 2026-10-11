package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class IcarusCureGameTest extends com.imdomestic.chorus.test.IcarusCureGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:icarus_cure_weapon",maxTicks=30) @Override
    public void realWeaponKillsHealAfterEarlierDeathReactionLandsAttackerAndDiscardsCorpse(GameTestHelper h) throws Exception {super.realWeaponKillsHealAfterEarlierDeathReactionLandsAttackerAndDiscardsCorpse(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:icarus_cure_impact",maxTicks=30) @Override
    public void airborneEligibilityIsCapturedAtImpactAndDoesNotUseLaunchOrLaterFlags(GameTestHelper h) throws Exception {super.airborneEligibilityIsCapturedAtImpactAndDoesNotUseLaunchOrLaterFlags(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:icarus_cure_cooldown",maxTicks=45) @Override
    public void realSuperKillsShareCureCooldownWithAnIndependentProducer(GameTestHelper h) throws Exception {super.realSuperKillsShareCureCooldownWithAnIndependentProducer(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:icarus_cure_gap",maxTicks=180) @Override
    public void realFiveSecondKillGapsRefreshProgressWithoutRetainingOldReceiptBuckets(GameTestHelper h) throws Exception {super.realFiveSecondKillGapsRefreshProgressWithoutRetainingOldReceiptBuckets(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualSelectionRemovalClearsCounterAndOtherDamageDoesNotCount(GameTestHelper h) throws Exception {super.actualSelectionRemovalClearsCounterAndOtherDamageDoesNotCount(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:icarus_cure_failure",maxTicks=30) @Override
    public void unknownNativeCureKeepsAppliedHealthAndConsumedProgressWithoutReplay(GameTestHelper h) throws Exception {super.unknownNativeCureKeepsAppliedHealthAndConsumedProgressWithoutReplay(h);}
}
