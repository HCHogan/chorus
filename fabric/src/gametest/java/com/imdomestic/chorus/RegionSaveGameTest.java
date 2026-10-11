package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class RegionSaveGameTest extends com.imdomestic.chorus.test.RegionSaveGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void aSupplierFailureReturnsFailedEvidenceEvenWhenTheNativeFutureNeverEnds(GameTestHelper h) throws Exception {
        super.aSupplierFailureReturnsFailedEvidenceEvenWhenTheNativeFutureNeverEnds(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void writesAndDeletesNeedARealFlushBeforeTheyCanConfirmStorage(GameTestHelper h) throws Exception {
        super.writesAndDeletesNeedARealFlushBeforeTheyCanConfirmStorage(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void anEarlierWriteFailureSurvivesASuccessfulFlushAndCannotConfirmTheArchive(GameTestHelper h) throws Exception {
        super.anEarlierWriteFailureSurvivesASuccessfulFlushAndCannotConfirmTheArchive(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void queuedAndCoalescedWritesRemainPendingUntilTheirActualCompletion(GameTestHelper h) throws Exception {
        super.queuedAndCoalescedWritesRemainPendingUntilTheirActualCompletion(h);
    }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void realFlushAndSwallowedCloseErrorsInvalidateAnEarlierSuccessfulReport(GameTestHelper h) throws Exception {
        super.realFlushAndSwallowedCloseErrorsInvalidateAnEarlierSuccessfulReport(h);
    }
}
