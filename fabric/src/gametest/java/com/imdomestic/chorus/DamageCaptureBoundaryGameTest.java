package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DamageCaptureBoundaryGameTest extends com.imdomestic.chorus.test.DamageCaptureBoundaryGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void beginRejectionCannotHurtAndCleanupFailuresDoNotLeakTheNextCapture(GameTestHelper h){super.beginRejectionCannotHurtAndCleanupFailuresDoNotLeakTheNextCapture(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void preparationAndDescriptionFailuresAreReportedBeforeNativeHurtAndReleaseTheBoundary(GameTestHelper h){super.preparationAndDescriptionFailuresAreReportedBeforeNativeHurtAndReleaseTheBoundary(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void nestedBeginRejectionRetainsTheEarlierActualHitAndFlushesOnlyAtTheOuterReturn(GameTestHelper h){super.nestedBeginRejectionRetainsTheEarlierActualHitAndFlushesOnlyAtTheOuterReturn(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void rejectedSourceRevisionReceivesActualObjectsAndRestoresTheDelegationLayer(GameTestHelper h){super.rejectedSourceRevisionReceivesActualObjectsAndRestoresTheDelegationLayer(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void managedPrimaryActualReceiptIsDiagnosticOnFailureAndNotPublishedAgainOnSuccess(GameTestHelper h){super.managedPrimaryActualReceiptIsDiagnosticOnFailureAndNotPublishedAgainOnSuccess(h);}
}
