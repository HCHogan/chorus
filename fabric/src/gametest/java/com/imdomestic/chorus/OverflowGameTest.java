package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class OverflowGameTest extends com.imdomestic.chorus.test.OverflowGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:overflow_special", maxTicks=60) @Override
    public void specialBrickTriggersNormalEnhancedAndOtherAmmoWeaponsWhileStowedThenFeedsActualFire(GameTestHelper h) throws Exception { super.specialBrickTriggersNormalEnhancedAndOtherAmmoWeaponsWhileStowedThenFeedsActualFire(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:overflow_heavy", maxTicks=60) @Override
    public void heavyBrickCannotSupplyEmptySpecialReservesButStillActivatesPrimaryOverflow(GameTestHelper h) throws Exception { super.heavyBrickCannotSupplyEmptySpecialReservesButStillActivatesPrimaryOverflow(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:overflow_full", maxTicks=60) @Override
    public void fullSpecialBrickWaitsForActualManualReloadThenGrantsReservesBeforeOverflow(GameTestHelper h) throws Exception { super.fullSpecialBrickWaitsForActualManualReloadThenGrantsReservesBeforeOverflow(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:overflow_replace", maxTicks=60) @Override
    public void replacingWeaponsBeforeContactUsesNewAllocationAndOnlyCurrentPerks(GameTestHelper h) throws Exception { super.replacingWeaponsBeforeContactUsesNewAllocationAndOnlyCurrentPerks(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:overflow_multiple", maxTicks=60) @Override
    public void successiveBricksUseSpaceFreedByOverflowThenRetainTheFirstUnneededUnit(GameTestHelper h) throws Exception { super.successiveBricksUseSpaceFreedByOverflowThenRetainTheFirstUnneededUnit(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:overflow_failure", maxTicks=60) @Override
    public void unknownPostRefillWorldReactionKeepsTransferredAmmoAndNeverReclaimsBrick(GameTestHelper h) throws Exception { super.unknownPostRefillWorldReactionKeepsTransferredAmmoAndNeverReclaimsBrick(h); }
}
