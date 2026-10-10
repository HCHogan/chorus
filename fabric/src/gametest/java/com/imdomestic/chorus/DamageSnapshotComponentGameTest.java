package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DamageSnapshotComponentGameTest extends com.imdomestic.chorus.test.DamageSnapshotComponentGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:stored_snapshot", maxTicks = 30) @Override
    public void independentBuffEventAfterSourceExpiryAndDetachUsesStoredAttackAndCurrentTargetDefense(GameTestHelper h) throws Exception { super.independentBuffEventAfterSourceExpiryAndDetachUsesStoredAttackAndCurrentTargetDefense(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void endedGenerationUsesItsSnapshotAndTheReplacementStartsEmpty(GameTestHelper h) throws Exception { super.endedGenerationUsesItsSnapshotAndTheReplacementStartsEmpty(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownDamageReceiptKeepsCommittedHealthAndSnapshotWithoutRepeatingTheWorldAction(GameTestHelper h) throws Exception { super.unknownDamageReceiptKeepsCommittedHealthAndSnapshotWithoutRepeatingTheWorldAction(h); }
}
