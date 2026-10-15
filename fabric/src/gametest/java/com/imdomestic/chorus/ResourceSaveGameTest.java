package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ResourceSaveGameTest extends com.imdomestic.chorus.test.ResourceSaveGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_save") @Override
    public void entityLoadingReadsAllRestoredDomainsBeforeTheirNativeLevelsAreConnected(GameTestHelper h){super.entityLoadingReadsAllRestoredDomainsBeforeTheirNativeLevelsAreConnected(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_save") @Override
    public void laterNativeConnectionFailureRetainsEvidenceUntilWholeGroupCleanup(GameTestHelper h){super.laterNativeConnectionFailureRetainsEvidenceUntilWholeGroupCleanup(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_save") @Override
    public void catalogueSaveCombinesResourceDirectoryNativeMetadataAndUnrelatedLocalDimensions(GameTestHelper h){super.catalogueSaveCombinesResourceDirectoryNativeMetadataAndUnrelatedLocalDimensions(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_save") @Override
    public void frozenNativeSaveRejectsDirectoryOperationsEvenWhenCallerCatchesTheError(GameTestHelper h){super.frozenNativeSaveRejectsDirectoryOperationsEvenWhenCallerCatchesTheError(h);}
}
