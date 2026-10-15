package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeCombatDomainsGameTest extends com.imdomestic.chorus.test.NativeCombatDomainsGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime",maxTicks=80) @Override
    public void remoteParticipantsRemainAfterResourceOnlyTransfer(GameTestHelper h) { super.remoteParticipantsRemainAfterResourceOnlyTransfer(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime",maxTicks=80) @Override
    public void nestedRemoteChildSurvivesUnknownParentWithSharedReservation(GameTestHelper h) { super.nestedRemoteChildSurvivesUnknownParentWithSharedReservation(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime",maxTicks=80) @Override
    public void multipleNativeRootsCommitAfterOneManagedCallback(GameTestHelper h) { super.multipleNativeRootsCommitAfterOneManagedCallback(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime",maxTicks=80) @Override
    public void lostManagedCallbackRetainsBothNativeRootsWithoutReceipt(GameTestHelper h) { super.lostManagedCallbackRetainsBothNativeRootsWithoutReceipt(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime",maxTicks=80) @Override
    public void managedPrimaryAcrossDomainsPublishesDamageAndConsumptionFactsOnce(GameTestHelper h) { super.managedPrimaryAcrossDomainsPublishesDamageAndConsumptionFactsOnce(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime",maxTicks=80) @Override
    public void removedSourceBodyKeepsHistoricalCreditAcrossNativeRoots(GameTestHelper h) { super.removedSourceBodyKeepsHistoricalCreditAcrossNativeRoots(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime",maxTicks=80) @Override
    public void replacementSourceBodyCannotJoinAnAlreadyCapturedNativeBoundary(GameTestHelper h) { super.replacementSourceBodyCannotJoinAnAlreadyCapturedNativeBoundary(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime",maxTicks=80) @Override
    public void swallowedPostDamageMutationRetainsActualReceiptAndConsumption(GameTestHelper h) { super.swallowedPostDamageMutationRetainsActualReceiptAndConsumption(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime",maxTicks=80) @Override
    public void failedPostCommitReactionStopsTheWholeHostWithoutLosingCombat(GameTestHelper h) { super.failedPostCommitReactionStopsTheWholeHostWithoutLosingCombat(h); }
}
