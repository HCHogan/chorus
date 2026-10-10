package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AbilityGameTest extends com.imdomestic.chorus.test.AbilityGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void conversionCommandsSpendTheSelectedPoolWhileCreditUsesTheResolvedAbility(GameTestHelper h) throws Exception { super.conversionCommandsSpendTheSelectedPoolWhileCreditUsesTheResolvedAbility(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:ability_selected_refund", maxTicks=15) @Override
    public void conversionRefundAcrossRealTicksRetainsOriginalAccountAfterSelectionChanges(GameTestHelper h) throws Exception { super.conversionRefundAcrossRealTicksRetainsOriginalAccountAfterSelectionChanges(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void conversionMissingCostAndUnknownWorldOutcomeNeverFallBackToAnotherPool(GameTestHelper h) throws Exception { super.conversionMissingCostAndUnknownWorldOutcomeNeverFallBackToAnotherPool(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void selectionCommandsOwnPassiveSourcesWhileReplacementCastsKeepTheBaseSelection(GameTestHelper h) throws Exception { super.selectionCommandsOwnPassiveSourcesWhileReplacementCastsKeepTheBaseSelection(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ability_effects", maxTicks = 16) @Override
    public void selectedPassivesScaleRealTickRegenerationAndSwapTimersWithOldCleanup(GameTestHelper h) throws Exception { super.selectedPassivesScaleRealTickRegenerationAndSwapTimersWithOldCleanup(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void selfInputRequiresAuthorizedSelectionAndConsumesBeforeActualHealing(GameTestHelper h) throws Exception { super.selfInputRequiresAuthorizedSelectionAndConsumesBeforeActualHealing(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:ability_delay", maxTicks = 12) @Override
    public void realTicksRegenerateAndDetachedCastKeepsAcceptedParametersAfterSelectionAndSourceChanges(GameTestHelper h) throws Exception { super.realTicksRegenerateAndDetachedCastKeepsAcceptedParametersAfterSelectionAndSourceChanges(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void serverMovementFactsChooseTemporaryReplacementAndDeadPlayersCannotSpend(GameTestHelper h) throws Exception { super.serverMovementFactsChooseTemporaryReplacementAndDeadPlayersCannotSpend(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void unknownWorldOutcomeRetainsPaidCostAndCannotReplayTheAcceptedCast(GameTestHelper h) throws Exception { super.unknownWorldOutcomeRetainsPaidCostAndCannotReplayTheAcceptedCast(h); }
}
