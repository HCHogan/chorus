package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DrawnWeaponGameTest extends com.imdomestic.chorus.test.DrawnWeaponGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:drawn_refill", maxTicks = 20) @Override
    public void actualReadiedContainerWeaponGetsRefillWithoutTouchingStowedWeaponsOrReloadPerks(GameTestHelper h) throws Exception { super.actualReadiedContainerWeaponGetsRefillWithoutTouchingStowedWeaponsOrReloadPerks(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:drawn_later", maxTicks = 15) @Override
    public void deferredQueryUsesWeaponDrawnAtExecutionEvenAfterProviderRemoval(GameTestHelper h) throws Exception { super.deferredQueryUsesWeaponDrawnAtExecutionEvenAfterProviderRemoval(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:drawn_captured", maxTicks = 15) @Override
    public void capturedSelectionRemainsTheOriginallyReadiedWeaponAfterSwitch(GameTestHelper h) throws Exception { super.capturedSelectionRemainsTheOriginallyReadiedWeaponAfterSwitch(h); }
}
