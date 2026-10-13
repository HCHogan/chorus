package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EquipmentConfigureGameTest extends com.imdomestic.chorus.test.EquipmentConfigureGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void ordinaryPlayerEditsOneSourceAndPreservesAllOwnedComponentsAcrossSave(GameTestHelper h) throws Exception { super.ordinaryPlayerEditsOneSourceAndPreservesAllOwnedComponentsAcrossSave(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void playerCommandsCannotEditFixedRollsExceedBudgetsOrForgeAccessAndMetadata(GameTestHelper h) throws Exception { super.playerCommandsCannotEditFixedRollsExceedBudgetsOrForgeAccessAndMetadata(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownCleanupRetainsTheCommittedChoicesAndWorldWriteWithoutReplay(GameTestHelper h) throws Exception { super.unknownCleanupRetainsTheCommittedChoicesAndWorldWriteWithoutReplay(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:equipment_configure_flinch", maxTicks=40) @Override
    public void changingArmorModsKeepsTheWeaponAmmoAimAndActivePerkWhileChangingRealFlinch(GameTestHelper h) throws Exception { super.changingArmorModsKeepsTheWeaponAmmoAimAndActivePerkWhileChangingRealFlinch(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:equipment_configure_repair", maxTicks=20) @Override
    public void allExpiredArtifactModsCanBeRepairedInOnePhysicalTransaction(GameTestHelper h) throws Exception { super.allExpiredArtifactModsCanBeRepairedInOnePhysicalTransaction(h); }
}
