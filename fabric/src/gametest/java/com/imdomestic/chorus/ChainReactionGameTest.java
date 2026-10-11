package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ChainReactionGameTest extends com.imdomestic.chorus.test.ChainReactionGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:chain_variants", maxTicks = 100) @Override
    public void bothAmmoRadiiAndEnhancedDamageUsePhysicalVictimsAndExcludeAllies(GameTestHelper h) throws Exception { super.bothAmmoRadiiAndEnhancedDamageUsePhysicalVictimsAndExcludeAllies(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:chain_history", maxTicks = 40) @Override
    public void delayedBlastSurvivesCorpseRemovalAndPerkReplacementWithCapturedBonus(GameTestHelper h) throws Exception { super.delayedBlastSurvivesCorpseRemovalAndPerkReplacementWithCapturedBonus(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:chain_feedback", maxTicks = 50) @Override
    public void derivedPhysicalKillsContinueAtEachNewCenterWithoutGlobalSuppression(GameTestHelper h) throws Exception { super.derivedPhysicalKillsContinueAtEachNewCenterWithoutGlobalSuppression(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:chain_eligibility", maxTicks = 100) @Override
    public void threadlingExclusionAndInFlightRemovalRespectActualWeaponEligibility(GameTestHelper h) throws Exception { super.threadlingExclusionAndInFlightRemovalRespectActualWeaponEligibility(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:chain_unknown", maxTicks = 40) @Override
    public void unknownNativeBlastResultRetainsDamageWithoutReplaying(GameTestHelper h) throws Exception { super.unknownNativeBlastResultRetainsDamageWithoutReplaying(h); }
}
