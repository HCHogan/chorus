package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AbilityCrossSlotCostGameTest extends com.imdomestic.chorus.test.AbilityCrossSlotCostGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void serverCommandPaysCurrentOtherSelectionWhileOnlyTheAirActionRuns(GameTestHelper h) throws Exception { super.serverCommandPaysCurrentOtherSelectionWhileOnlyTheAirActionRuns(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void groundedMissingCostAndExhaustedInputsDoNotRunWorldEffects(GameTestHelper h) throws Exception { super.groundedMissingCostAndExhaustedInputsDoNotRunWorldEffects(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:cross_slot_refund", maxTicks=12) @Override
    public void realTicksRefundThePaidAccountAfterBothSelectionsChange(GameTestHelper h) throws Exception { super.realTicksRefundThePaidAccountAfterBothSelectionsChange(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownWorldResultKeepsPaidEnergyAndActualHealingWithoutReplay(GameTestHelper h) throws Exception { super.unknownWorldResultKeepsPaidEnergyAndActualHealingWithoutReplay(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void airActionPaysRealDodgeAccountAndTriggersEquippedAttractionOnlyOnce(GameTestHelper h) throws Exception { super.airActionPaysRealDodgeAccountAndTriggersEquippedAttractionOnlyOnce(h); }
}
