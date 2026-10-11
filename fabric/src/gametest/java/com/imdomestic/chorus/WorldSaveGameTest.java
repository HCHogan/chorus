package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WorldSaveGameTest extends com.imdomestic.chorus.test.WorldSaveGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void bindingsResolveDistinctActualStoragesAndRejectAnotherThread(GameTestHelper h) throws Exception { super.bindingsResolveDistinctActualStoragesAndRejectAnotherThread(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void aDisabledDimensionRejectsTheSaveWithoutClearingItsSettingOrSchedulingWrites(GameTestHelper h) { super.aDisabledDimensionRejectsTheSaveWithoutClearingItsSettingOrSchedulingWrites(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:world_save") @Override
    public void realServerSaveReadsBackStateAndRejectsFailedPlayerFilesOrChangedMembership(GameTestHelper h) throws Exception { super.realServerSaveReadsBackStateAndRejectsFailedPlayerFilesOrChangedMembership(h); }
}
