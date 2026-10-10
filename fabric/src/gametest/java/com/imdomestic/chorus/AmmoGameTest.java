package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AmmoGameTest extends com.imdomestic.chorus.test.AmmoGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void independentWeaponsCommitConservedRefillsBeforeNativeActionsWithoutFakingReload(GameTestHelper h) throws Exception { super.independentWeaponsCommitConservedRefillsBeforeNativeActionsWithoutFakingReload(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ammunition_delay", maxTicks = 60) @Override
    public void detachedRefillRunsOnRealTicksAfterUnbindingAndRetainsExistingOverflow(GameTestHelper h) throws Exception { super.detachedRefillRunsOnRealTicksAfterUnbindingAndRetainsExistingOverflow(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownWorldOutcomeRetainsTransferredAmmoAndCannotReplay(GameTestHelper h) throws Exception { super.unknownWorldOutcomeRetainsTransferredAmmoAndCannotReplay(h); }
}
