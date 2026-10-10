package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DamageBatchGameTest extends com.imdomestic.chorus.test.DamageBatchGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void realManagedComponentsCountOncePerDeclaredBatchAndDoNotMergeTheNextAttack(GameTestHelper h) throws Exception { super.realManagedComponentsCountOncePerDeclaredBatchAndDoNotMergeTheNextAttack(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeAdapterCanDeclareMembershipAndUngroupedNativeHitsStayIndependent(GameTestHelper h) throws Exception { super.nativeAdapterCanDeclareMembershipAndUngroupedNativeHitsStayIndependent(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:batch_projectile", maxTicks = 25) @Override
    public void actualProjectileGroupsTwoImpactComponentsAfterItsSourceIsRemoved(GameTestHelper h) throws Exception { super.actualProjectileGroupsTwoImpactComponentsAfterItsSourceIsRemoved(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:batch_delayed", maxTicks = 20) @Override
    public void explicitlySharedLogicalBatchSurvivesDelayWhileUnboundSnapshotGetsAnotherBatch(GameTestHelper h) throws Exception { super.explicitlySharedLogicalBatchSurvivesDelayWhileUnboundSnapshotGetsAnotherBatch(h); }
}
