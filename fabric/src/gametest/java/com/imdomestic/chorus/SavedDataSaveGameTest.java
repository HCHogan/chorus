package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SavedDataSaveGameTest extends com.imdomestic.chorus.test.SavedDataSaveGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualFilesCompleteAcrossIndividualAndPartitionedBatches(GameTestHelper h) throws Exception {
        super.actualFilesCompleteAcrossIndividualAndPartitionedBatches(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void swallowedDirectoryFailureAndClearedDirtyFlagsCannotConfirmTheArchive(GameTestHelper h) throws Exception {
        super.swallowedDirectoryFailureAndClearedDirtyFlagsCannotConfirmTheArchive(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void aCaughtNbtWriteFailureIsDistinctFromADirectoryPreparationFailure(GameTestHelper h) throws Exception {
        super.aCaughtNbtWriteFailureIsDistinctFromADirectoryPreparationFailure(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void emptySaveAndNativeCloseDoNotAwaitAnEarlierBatchButTheJournalDoes(GameTestHelper h) throws Exception {
        super.emptySaveAndNativeCloseDoNotAwaitAnEarlierBatchButTheJournalDoes(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void encodingFailureRetainsEvidenceAfterEarlierEntriesLostTheirDirtyFlag(GameTestHelper h) throws Exception {
        super.encodingFailureRetainsEvidenceAfterEarlierEntriesLostTheirDirtyFlag(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void anEarlierExceptionalBatchPreventsChainedFilesAndCannotBecomeAnEmptySuccess(GameTestHelper h) throws Exception {
        super.anEarlierExceptionalBatchPreventsChainedFilesAndCannotBecomeAnEmptySuccess(h);
    }
}
