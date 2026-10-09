package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Fabric registration only; scenarios are shared with NeoForge. */
public class NativeRuntimeGameTest extends com.imdomestic.chorus.test.NativeRuntimeGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeDamageHasActualAttributionAndDetachStopsObservation(GameTestHelper helper) { super.nativeDamageHasActualAttributionAndDetachStopsObservation(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeProtectionAndBypassedProtectionHaveDifferentFacts(GameTestHelper helper) { super.nativeProtectionAndBypassedProtectionHaveDifferentFacts(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void managedDamageAndNativeReentryPublishEachHitOnce(GameTestHelper helper) { super.managedDamageAndNativeReentryPublishEachHitOnce(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeReentryIsOneBatchWithSeparateLosses(GameTestHelper helper) { super.nativeReentryIsOneBatchWithSeparateLosses(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void playerDelegationAndEarlyImmunityDoNotDuplicateFacts(GameTestHelper helper) { super.playerDelegationAndEarlyImmunityDoNotDuplicateFacts(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeExceptionRetainsChildEvidenceAndDisablesFurtherDerivation(GameTestHelper helper) { super.nativeExceptionRetainsChildEvidenceAndDisablesFurtherDerivation(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void worldFailureDoesNotReplayUnknownOperation(GameTestHelper helper) { super.worldFailureDoesNotReplayUnknownOperation(helper); }
    @GameTest(structure = "chorus_gametest:empty", dimension = "minecraft:the_nether", maxTicks = 15) @Override
    public void serverTickAutomaticallyExpiresBuffAtLogicalDeadline(GameTestHelper helper) { super.serverTickAutomaticallyExpiresBuffAtLogicalDeadline(helper); }
}
