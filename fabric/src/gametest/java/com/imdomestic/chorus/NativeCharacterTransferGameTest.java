package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeCharacterTransferGameTest extends com.imdomestic.chorus.test.NativeCharacterTransferGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_character_roundtrip",maxTicks=30) @Override
    public void automaticRoundTripKeepsContainersBuffsPaidEnergyAndRemainingReload(GameTestHelper h){super.automaticRoundTripKeepsContainersBuffsPaidEnergyAndRemainingReload(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void unequippedNativeInventoryWeaponCarriesItsExistingAmmoAndShotCadence(GameTestHelper h){super.unequippedNativeInventoryWeaponCarriesItsExistingAmmoAndShotCadence(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_character_restore",maxTicks=30) @Override
    public void transferredCharacterArchiveRestoresBothNativeDomainsAndCompletesRemainingReloadOnce(GameTestHelper h){super.transferredCharacterArchiveRestoresBothNativeDomainsAndCompletesRemainingReloadOnce(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void destinationOwnershipConflictRejectsBeforeTheNativePlayerLeavesItsWorld(GameTestHelper h){super.destinationOwnershipConflictRejectsBeforeTheNativePlayerLeavesItsWorld(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void postTeleportFailureRetainsActualArrivalAndPublishedStateWithoutRetryOrCheckpoint(GameTestHelper h){super.postTeleportFailureRetainsActualArrivalAndPublishedStateWithoutRetryOrCheckpoint(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void failureAfterNativeRemovalRetainsSourceCoreAndActualRemovalWithoutCheckpoint(GameTestHelper h){super.failureAfterNativeRemovalRetainsSourceCoreAndActualRemovalWithoutCheckpoint(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void caughtFencedWriteStillRejectsPublicationAfterActualDestinationInsertion(GameTestHelper h){super.caughtFencedWriteStillRejectsPublicationAfterActualDestinationInsertion(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void postTeleportMayStartAnotherTransferAfterTheFirstCharacterPublication(GameTestHelper h){super.postTeleportMayStartAnotherTransferAfterTheFirstCharacterPublication(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void ordinarySameDimensionTeleportKeepsTheExistingResourceLeaseAndCharacter(GameTestHelper h){super.ordinarySameDimensionTeleportKeepsTheExistingResourceLeaseAndCharacter(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void nativeRemovedPlayerRejectionDoesNotStartOrFailACharacterTransfer(GameTestHelper h){super.nativeRemovedPlayerRejectionDoesNotStartOrFailACharacterTransfer(h);}
}
