package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class HarvestGameTest extends com.imdomestic.chorus.test.HarvestGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:harvest_paid", maxTicks = 20) @Override
    public void paidShieldKillsAnAllysWeakenedTargetAndBothPhysicalPickupsActivateDevour(GameTestHelper h) throws Exception { super.paidShieldKillsAnAllysWeakenedTargetAndBothPhysicalPickupsActivateDevour(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:harvest_history", maxTicks = 20) @Override
    public void DeathReceiptKeepsWeakenAndLocationAfterCorpseRemovalAndNativeNonprecisionKillsQualify(GameTestHelper h) throws Exception { super.DeathReceiptKeepsWeakenAndLocationAfterCorpseRemovalAndNativeNonprecisionKillsQualify(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:harvest_live", maxTicks = 20) @Override
    public void currentFragmentAtPhysicalImpactControlsGenerationAndDuplicateSourcesProduceOnePair(GameTestHelper h) throws Exception { super.currentFragmentAtPhysicalImpactControlsGenerationAndDuplicateSourcesProduceOnePair(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:harvest_cooldown", maxTicks = 230) @Override
    public void physicalCollectionAndRebindingDoNotResetTenSecondGenerationCooldown(GameTestHelper h) throws Exception { super.physicalCollectionAndRebindingDoNotResetTenSecondGenerationCooldown(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:harvest_breach", maxTicks = 20) @Override
    public void anotherBreachProducerSharesOnlyBreachCooldownWhileHarvestOrbRemainsIndependent(GameTestHelper h) throws Exception { super.anotherBreachProducerSharesOnlyBreachCooldownWhileHarvestOrbRemainsIndependent(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:harvest_enhanced", maxTicks = 20) @Override
    public void paidVoidKillThenItsTwoPickupsComposeWithFeedTheVoidWithoutExtraKillEnergy(GameTestHelper h) throws Exception { super.paidVoidKillThenItsTwoPickupsComposeWithFeedTheVoidWithoutExtraKillEnergy(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:harvest_rejected", maxTicks = 20) @Override
    public void recipientRemovedAfterActualDeathRejectsBothSpawnsWithoutConsumingCooldown(GameTestHelper h) throws Exception { super.recipientRemovedAfterActualDeathRejectsBothSpawnsWithoutConsumingCooldown(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:harvest_unknown", maxTicks = 20) @Override
    public void unknownReceiptAfterActualOrbCreationStopsBeforeBreachAndNeverReplaysSpawn(GameTestHelper h) throws Exception { super.unknownReceiptAfterActualOrbCreationStopsBeforeBreachAndNeverReplaysSpawn(h); }
}
