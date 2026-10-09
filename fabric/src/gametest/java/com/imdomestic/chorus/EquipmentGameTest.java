package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EquipmentGameTest extends com.imdomestic.chorus.test.EquipmentGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void equipmentProjectionDrivesActualNativeDamageAndRejectsInvalidChangesBeforeCommit(GameTestHelper h) throws Exception { super.equipmentProjectionDrivesActualNativeDamageAndRejectsInvalidChangesBeforeCommit(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:equipment_switch", maxTicks = 22) @Override
    public void realTicksKeepPassiveTimersAndPauseThenResumeWeaponBoundBuffLifetime(GameTestHelper h) throws Exception { super.realTicksKeepPassiveTimersAndPauseThenResumeWeaponBoundBuffLifetime(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void failureAfterCleanupKeepsCommittedReplacementAndDoesNotRetryWorldHealing(GameTestHelper h) throws Exception { super.failureAfterCleanupKeepsCommittedReplacementAndDoesNotRetryWorldHealing(h); }
}
