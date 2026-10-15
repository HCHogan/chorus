package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeInputTransferGameTest extends com.imdomestic.chorus.test.NativeInputTransferGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void preparedNetworkHandoffsFreezeEdgesAndAbortPreservesOriginalGestures(GameTestHelper h){super.preparedNetworkHandoffsFreezeEdgesAndAbortPreservesOriginalGestures(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_input_transfer",maxTicks=15) @Override
    public void nativeTransferKeepsAimChargeAndAbilityGesturesButRejectsOldDimensionEdges(GameTestHelper h){super.nativeTransferKeepsAimChargeAndAbilityGesturesButRejectsOldDimensionEdges(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_input_lease_transfer",maxTicks=50) @Override
    public void nativeTransferPreservesAllInputLeasesAndExpiredHoldsRecoverWithFreshPress(GameTestHelper h){super.nativeTransferPreservesAllInputLeasesAndExpiredHoldsRecoverWithFreshPress(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_ability_input_lease",maxTicks=80) @Override
    public void abilityHeartbeatsRenewOnlyTheMatchingMigratedGestureAndServerTicksRetireSilence(GameTestHelper h){super.abilityHeartbeatsRenewOnlyTheMatchingMigratedGestureAndServerTicksRetireSilence(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void sameTickRoundTripRejectsOriginalDimensionPacketsUntilTheirExplicitEpochMatches(GameTestHelper h){super.sameTickRoundTripRejectsOriginalDimensionPacketsUntilTheirExplicitEpochMatches(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void actualRespawnRetiresStaleAbilityMetadataBeforeTheNewLifeTeleports(GameTestHelper h){super.actualRespawnRetiresStaleAbilityMetadataBeforeTheNewLifeTeleports(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void transferConfirmationsRoundTripAllGestureKindsAndRejectAmbiguousAuthorizations(GameTestHelper h){super.transferConfirmationsRoundTripAllGestureKindsAndRejectAmbiguousAuthorizations(h);}
}
