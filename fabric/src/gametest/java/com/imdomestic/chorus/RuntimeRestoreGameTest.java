package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class RuntimeRestoreGameTest extends com.imdomestic.chorus.test.RuntimeRestoreGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void archivedCatalogueMetadataRestoresAfterExplicitEntityFileConfirmations(GameTestHelper h) throws Exception {
        super.archivedCatalogueMetadataRestoresAfterExplicitEntityFileConfirmations(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void catalogueRestorePreservesPaidEnergyAndDoesNotRepeatSourceInitialization(GameTestHelper h) {
        super.catalogueRestorePreservesPaidEnergyAndDoesNotRepeatSourceInitialization(h);
    }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:runtime_restore_clock", maxTicks = 14) @Override
    public void restoredProjectionsPreserveForeignModifiersAndExpireOnTheOriginalClock(GameTestHelper h) throws Exception {
        super.restoredProjectionsPreserveForeignModifiersAndExpireOnTheOriginalClock(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void wrongWorldCatalogueAndDuplicateRuntimeAreRejectedBeforeAttachment(GameTestHelper h) {
        super.wrongWorldCatalogueAndDuplicateRuntimeAreRejectedBeforeAttachment(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void sourceDescriptionAndWorldExecutionCannotCaptureAnUnsettledCheckpoint(GameTestHelper h) throws Exception {
        super.sourceDescriptionAndWorldExecutionCannotCaptureAnUnsettledCheckpoint(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownWorldHealingCannotBecomeARestorableHostCheckpoint(GameTestHelper h) throws Exception {
        super.unknownWorldHealingCannotBecomeARestorableHostCheckpoint(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void physicalEquipmentMustMatchWithoutMovingItemsOrRepeatingAttachEffects(GameTestHelper h) throws Exception {
        super.physicalEquipmentMustMatchWithoutMovingItemsOrRepeatingAttachEffects(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void trustedExternalEquipmentHostsAreNotReplacedByThePlayerContainer(GameTestHelper h) throws Exception {
        super.trustedExternalEquipmentHostsAreNotReplacedByThePlayerContainer(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void closingAndRestoringDoesNotRecreatePickupsOrInventRewards(GameTestHelper h) throws Exception {
        super.closingAndRestoringDoesNotRecreatePickupsOrInventRewards(h);
    }
}
