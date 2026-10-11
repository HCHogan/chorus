package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeSaveGameTest extends com.imdomestic.chorus.test.NativeSaveGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void playerSaveReportsAnActualReplacementOnlyAfterTheCaptureCloses(GameTestHelper h) throws Exception {
        super.playerSaveReportsAnActualReplacementOnlyAfterTheCaptureCloses(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void aSwallowedFailureBeforeTemporaryFileCreationIsNotSuccessful(GameTestHelper h) throws Exception {
        super.aSwallowedFailureBeforeTemporaryFileCreationIsNotSuccessful(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void failedPlayerReplacementKeepsTheOldFileAndCannotBeHiddenByALaterSuccess(GameTestHelper h) throws Exception {
        super.failedPlayerReplacementKeepsTheOldFileAndCannotBeHiddenByALaterSuccess(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void levelDatReplacementFailureIsObservedDespiteItsVoidReturn(GameTestHelper h) throws Exception {
        super.levelDatReplacementFailureIsObservedDespiteItsVoidReturn(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nestedCapturesTrackExactExpectedPlayersAndDoNotCountAnotherPlayersFile(GameTestHelper h) throws Exception {
        super.nestedCapturesTrackExactExpectedPlayersAndDoNotCountAnotherPlayersFile(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeFailureCannotConfirmTheWorldCheckpointStore(GameTestHelper h) throws Exception {
        super.nativeFailureCannotConfirmTheWorldCheckpointStore(h);
    }
}
