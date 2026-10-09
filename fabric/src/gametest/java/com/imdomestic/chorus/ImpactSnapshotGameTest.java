package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ImpactSnapshotGameTest extends com.imdomestic.chorus.test.ImpactSnapshotGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:impact_snapshot") @Override
    public void delayedExplosionUsesFrozenBonusesAndPerTargetDistancesAfterQueryMovement(GameTestHelper h) throws Exception { super.delayedExplosionUsesFrozenBonusesAndPerTargetDistancesAfterQueryMovement(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeSnapshotUsesExplicitImpactForLiveMaxDefenseAndReceiptFacts(GameTestHelper h) throws Exception { super.nativeSnapshotUsesExplicitImpactForLiveMaxDefenseAndReceiptFacts(h); }
}
