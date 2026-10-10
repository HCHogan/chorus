package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class RampageGameTest extends com.imdomestic.chorus.test.RampageGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rampage_decay", maxTicks = 325) @Override
    public void physicalKillsBuildBothVariantsAndRealTicksDecayEachLayerWhileStowed(GameTestHelper h) throws Exception { super.physicalKillsBuildBothVariantsAndRealTicksDecayEachLayerWhileStowed(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:rampage_snapshot", maxTicks = 115) @Override
    public void shotLaunchedBeforeExpiryKeepsItsTierWhenImpactArrivesAfterStowAndExpiry(GameTestHelper h) throws Exception { super.shotLaunchedBeforeExpiryKeepsItsTierWhenImpactArrivesAfterStowAndExpiry(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualUncreditedKillAndPerkRemovedInFlightDoNotGrantStacks(GameTestHelper h) throws Exception { super.actualUncreditedKillAndPerkRemovedInFlightDoNotGrantStacks(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownLethalReceiptKeepsSpentAmmoAndDoesNotInventOrReplayAKillGrant(GameTestHelper h) throws Exception { super.unknownLethalReceiptKeepsSpentAmmoAndDoesNotInventOrReplayAKillGrant(h); }
}
