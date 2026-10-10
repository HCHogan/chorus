package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeAttributeGameTest extends com.imdomestic.chorus.test.NativeAttributeGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_attrs_lifecycle",maxTicks=20) @Override
    public void nativeContributionsPreserveBaseValuesOtherModsAndTheirOwnOperationOrder(GameTestHelper h)throws Exception{super.nativeContributionsPreserveBaseValuesOtherModsAndTheirOwnOperationOrder(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_attrs_expiry",maxTicks=14) @Override
    public void recipientBuffProjectsMovementAndGravityUntilItsActualExpiry(GameTestHelper h)throws Exception{super.recipientBuffProjectsMovementAndGravityUntilItsActualExpiry(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_attrs_world_boundary",maxTicks=20) @Override
    public void aFollowingWorldActionSeesNewAttributesAndUnknownResultsRetainThemUntilClose(GameTestHelper h)throws Exception{super.aFollowingWorldActionSeesNewAttributesAndUnknownResultsRetainThemUntilClose(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_attrs_atomic",maxTicks=20) @Override
    public void laterCalculationFailureCannotPartlyReplaceEarlierNativeAttributes(GameTestHelper h)throws Exception{super.laterCalculationFailureCannotPartlyReplaceEarlierNativeAttributes(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_attrs_eligibility",maxTicks=20) @Override
    public void missingUnsupportedAndIneligibleRecipientsAreReportedAndRegistryErrorsFailInstallation(GameTestHelper h)throws Exception{super.missingUnsupportedAndIneligibleRecipientsAreReportedAndRegistryErrorsFailInstallation(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_attrs_conflict",maxTicks=20) @Override
    public void anExternallyOverwrittenProjectionIsNeitherSilentlyReplacedNorDeleted(GameTestHelper h)throws Exception{super.anExternallyOverwrittenProjectionIsNeitherSilentlyReplacedNorDeleted(h);}
}
