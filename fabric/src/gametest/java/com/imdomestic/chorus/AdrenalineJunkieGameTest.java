package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AdrenalineJunkieGameTest extends com.imdomestic.chorus.test.AdrenalineJunkieGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void realWeaponKillsBuildOnlyTheirOwnWeaponDamageBuff(GameTestHelper helper) throws Exception { super.realWeaponKillsBuildOnlyTheirOwnWeaponDamageBuff(helper); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void realGrenadeKillActivatesBothStowedWeaponInstances(GameTestHelper helper) throws Exception { super.realGrenadeKillActivatesBothStowedWeaponInstances(helper); }
}
