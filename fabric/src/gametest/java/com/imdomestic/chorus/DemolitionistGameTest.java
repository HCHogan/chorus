package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class DemolitionistGameTest extends com.imdomestic.chorus.test.DemolitionistGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void actualWeaponKillsRestoreGrenadeAndAbilityRefillsDoNotTriggerKillClipOrClown(GameTestHelper h) throws Exception { super.actualWeaponKillsRestoreGrenadeAndAbilityRefillsDoNotTriggerKillClipOrClown(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:demolitionist_cooldown", maxTicks = 85) @Override
    public void serverTicksEnforceCooldownAndAlreadyFullManualReloadDoesNotFinishAsReload(GameTestHelper h) throws Exception { super.serverTicksEnforceCooldownAndAlreadyFullManualReloadDoesNotFinishAsReload(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void laterUnknownAbilityWorldOutcomeRetainsPaidEnergyRefillAndCooldown(GameTestHelper h) throws Exception { super.laterUnknownAbilityWorldOutcomeRetainsPaidEnergyRefillAndCooldown(h); }
}
