package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProgramImportsGameTest extends com.imdomestic.chorus.test.ProgramImportsGameTest {
    @GameTest(structure = "chorus_gametest:empty", maxTicks = 100) @Override
    public void importedVoltshotAndJoltExecuteFromTheReloadedCatalogue(GameTestHelper h) throws Exception { super.importedVoltshotAndJoltExecuteFromTheReloadedCatalogue(h); }
    @GameTest(structure = "chorus_gametest:empty", maxTicks = 150) @Override
    public void dependencyOverridePinsLiveRuntimeAndBrokenImportsRejectWholeReload(GameTestHelper h) throws Exception { super.dependencyOverridePinsLiveRuntimeAndBrokenImportsRejectWholeReload(h); }
}
