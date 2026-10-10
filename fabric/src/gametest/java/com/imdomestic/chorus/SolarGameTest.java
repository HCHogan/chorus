package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SolarGameTest extends com.imdomestic.chorus.test.SolarGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:solar_ticks",maxTicks=60) @Override
    public void actualTicksKeepFirstSourceAfterReapplicationAndUnbindingWithoutRestartingCadence(GameTestHelper h) throws Exception { super.actualTicksKeepFirstSourceAfterReapplicationAndUnbindingWithoutRestartingCadence(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void mixedSourcesIgniteOnceWithActualNativeKillAndSharedTargetLockout(GameTestHelper h) throws Exception { super.mixedSourcesIgniteOnceWithActualNativeKillAndSharedTargetLockout(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:solar_guardian",maxTicks=40) @Override
    public void explicitPvpNonlethalPolicyPreservesGuardianBeforeLethalIgnition(GameTestHelper h) throws Exception { super.explicitPvpNonlethalPolicyPreservesGuardianBeforeLethalIgnition(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void allowedIgnitionChainDamagesBothTargetsTwiceWithoutGlobalSuppression(GameTestHelper h) throws Exception { super.allowedIgnitionChainDamagesBothTargetsTwiceWithoutGlobalSuppression(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownIgnitionReceiptKeepsWorldDamageAndDoesNotRepeatExplosion(GameTestHelper h) throws Exception { super.unknownIgnitionReceiptKeepsWorldDamageAndDoesNotRepeatExplosion(h); }
}
