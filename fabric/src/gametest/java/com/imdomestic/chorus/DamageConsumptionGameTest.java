package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DamageConsumptionGameTest extends com.imdomestic.chorus.test.DamageConsumptionGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nextManagedDamageConsumesBeforeSecondRawOrSnapshotWorldCommand(GameTestHelper h) throws Exception { super.nextManagedDamageConsumesBeforeSecondRawOrSnapshotWorldCommand(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void knownMissingTargetPreservesBuffUntilNextSuccessfulWorldHit(GameTestHelper h) throws Exception { super.knownMissingTargetPreservesBuffUntilNextSuccessfulWorldHit(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownWorldReceiptPreservesPendingDamageAndDoesNotExecuteSecondCommand(GameTestHelper h) throws Exception { super.unknownWorldReceiptPreservesPendingDamageAndDoesNotExecuteSecondCommand(h); }
}
