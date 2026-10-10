package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ContinuityGameTest extends com.imdomestic.chorus.test.ContinuityGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void realSliceHitUsesOnlyItsAppliersCurrentFragmentAndDuplicateSourcesDoNotStack(GameTestHelper h) throws Exception { super.realSliceHitUsesOnlyItsAppliersCurrentFragmentAndDuplicateSourcesDoNotStack(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void deniedExtendedStatusDoesNotConsumeSliceOrChangeItsTimer(GameTestHelper h) throws Exception { super.deniedExtendedStatusDoesNotConsumeSliceOrChangeItsTimer(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:continuity_delay", maxTicks = 14) @Override
    public void delayedCalculatedValueSurvivesSourceRemovalAndLaterQueryUsesNewLoadout(GameTestHelper h) throws Exception { super.delayedCalculatedValueSurvivesSourceRemovalAndLaterQueryUsesNewLoadout(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:continuity_expiry", maxTicks = 170) @Override
    public void realTicksKeepExtendedSeverPastItsBaseTimeAfterFragmentRemovalThenExpireIt(GameTestHelper h) throws Exception { super.realTicksKeepExtendedSeverPastItsBaseTimeAfterFragmentRemovalThenExpireIt(h); }
}
