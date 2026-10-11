package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeTrackingGameTest extends com.imdomestic.chorus.test.NativeTrackingGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void absentRuntimeKeepsVanillaHomingAndActuallyMissingTargetsAreCleared(GameTestHelper h) { super.absentRuntimeKeepsVanillaHomingAndActuallyMissingTargetsAreCleared(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeTargetDenialPreservesGravityReferenceAndCapturedCreditUntilReacquisition(GameTestHelper h) { super.nativeTargetDenialPreservesGravityReferenceAndCapturedCreditUntilReacquisition(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actorGateAndContinueOnlyGateUseNativeOwnerAndPhase(GameTestHelper h) { super.actorGateAndContinueOnlyGateUseNativeOwnerAndPhase(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void deniedNativeSteeringStillHitsDamagesAndAppliesLevitationOnce(GameTestHelper h) { super.deniedNativeSteeringStillHitsDamagesAndAppliesLevitationOnce(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownNativeQueryStopsBeforeMovementAndAffectedSiblingsStopOnTheirNextTick(GameTestHelper h) { super.unknownNativeQueryStopsBeforeMovementAndAffectedSiblingsStopOnTheirNextTick(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeCreditSurvivesSaveLoadWithoutOwnerAndChangesWhenOwnerIsReassigned(GameTestHelper h) { super.nativeCreditSurvivesSaveLoadWithoutOwnerAndChangesWhenOwnerIsReassigned(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void mismatchedPersistedCreditCannotInventAnUnavailableOwnersClassification(GameTestHelper h) { super.mismatchedPersistedCreditCannotInventAnUnavailableOwnersClassification(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:native_gambler_expiry", maxTicks = 30) @Override
    public void actualDodgeBlocksNativeCombatantHomingUntilRealExpiry(GameTestHelper h) throws Exception { super.actualDodgeBlocksNativeCombatantHomingUntilRealExpiry(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualDodgeLeavesNativeGuardianHomingAndCombatantCollisionIntact(GameTestHelper h) throws Exception { super.actualDodgeLeavesNativeGuardianHomingAndCombatantCollisionIntact(h); }
}
