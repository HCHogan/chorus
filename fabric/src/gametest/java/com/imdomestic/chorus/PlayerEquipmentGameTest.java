package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PlayerEquipmentGameTest extends com.imdomestic.chorus.test.PlayerEquipmentGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void numericEquipmentRollsDriveNativeHealingAndSurviveSaveAndWire(GameTestHelper h) throws Exception { super.numericEquipmentRollsDriveNativeHealingAndSurviveSaveAndWire(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void invalidNumericRollsCannotMutatePhysicalEquipmentThroughClientRequests(GameTestHelper h) throws Exception { super.invalidNumericRollsCannotMutatePhysicalEquipmentThroughClientRequests(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void deathInsideAttachReactionDropsTheAlreadyTransferredItemWithoutResurrectingOwnership(GameTestHelper h) throws Exception { super.deathInsideAttachReactionDropsTheAlreadyTransferredItemWithoutResurrectingOwnership(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void commandsTransferOneOwnedStackPreserveComponentsAndValidateRevisionBeforeReactions(GameTestHelper h) throws Exception { super.commandsTransferOneOwnedStackPreserveComponentsAndValidateRevisionBeforeReactions(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void playerSaveRoundTripRetainsUnknownDefinitionsAndAllowsRetrievalWithoutAnActiveRuleset(GameTestHelper h) throws Exception { super.playerSaveRoundTripRetainsUnknownDefinitionsAndAllowsRetrievalWithoutAnActiveRuleset(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void deathProtectionKeepInventoryVanishingAndRespawnPreservePhysicalOwnership(GameTestHelper h) throws Exception { super.deathProtectionKeepInventoryVanishingAndRespawnPreservePhysicalOwnership(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownCleanupOutcomeRetainsTransferredItemsAndSupportsRecoveryWithoutRetry(GameTestHelper h) throws Exception { super.unknownCleanupOutcomeRetainsTransferredItemsAndSupportsRecoveryWithoutRetry(h); }
}
