package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SaveSerializationGameTest extends com.imdomestic.chorus.test.SaveSerializationGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void completeEntitiesAndIntentionalSkippedEntitiesRemainValid(GameTestHelper h) throws Exception { super.completeEntitiesAndIntentionalSkippedEntitiesRemainValid(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void reportedEntityFieldErrorsInvalidateOtherwiseSuccessfulRegionWrites(GameTestHelper h) throws Exception { super.reportedEntityFieldErrorsInvalidateOtherwiseSuccessfulRegionWrites(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void thrownEntityErrorsAreRecordedBeforeNeoForgeCanDiscardTheEntity(GameTestHelper h) throws Exception { super.thrownEntityErrorsAreRecordedBeforeNeoForgeCanDiscardTheEntity(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void sectionPartialAndMissingResultsRemainFailuresEvenAfterTheNativeDirtySetClears(GameTestHelper h) throws Exception { super.sectionPartialAndMissingResultsRemainFailuresEvenAfterTheNativeDirtySetClears(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void aSectionPackerExceptionBeforeWorkerSubmissionIsRetained(GameTestHelper h) throws Exception { super.aSectionPackerExceptionBeforeWorkerSubmissionIsRetained(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void chunkSnapshotsRetainBlockEntityWarningsAndNativeCaughtExceptions(GameTestHelper h) throws Exception { super.chunkSnapshotsRetainBlockEntityWarningsAndNativeCaughtExceptions(h); }
}
