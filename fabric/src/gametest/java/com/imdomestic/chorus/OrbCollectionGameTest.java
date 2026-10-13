package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class OrbCollectionGameTest extends com.imdomestic.chorus.test.OrbCollectionGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void physicalOrbBatchStopsWhenNestedArmorChargeReactionsFillThePool(GameTestHelper h) throws Exception { super.physicalOrbBatchStopsWhenNestedArmorChargeReactionsFillThePool(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void stacksOnStacksLeavesThreeOfFivePhysicalCandidatesUnclaimed(GameTestHelper h) throws Exception { super.stacksOnStacksLeavesThreeOfFivePhysicalCandidatesUnclaimed(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void starvationKeepsItsIndependentPermissionAndEachPhysicalOrbTriggersDevour(GameTestHelper h) throws Exception { super.starvationKeepsItsIndependentPermissionAndEachPhysicalOrbTriggersDevour(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownNestedDevourHealingDoesNotConsumeTheNextPhysicalOrb(GameTestHelper h) throws Exception { super.unknownNestedDevourHealingDoesNotConsumeTheNextPhysicalOrb(h); }
}
