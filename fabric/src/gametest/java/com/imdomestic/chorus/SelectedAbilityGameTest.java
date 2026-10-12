package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SelectedAbilityGameTest extends com.imdomestic.chorus.test.SelectedAbilityGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:selected_ability_identity") @Override
    public void actualHealingReadsTheCorrectHolderAndSlotWithoutPassiveTagMarkers(GameTestHelper h) throws Exception { super.actualHealingReadsTheCorrectHolderAndSlotWithoutPassiveTagMarkers(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:selected_ability_cast") @Override
    public void aReplacementCastKeepsBaseSelectionConditionsAndResolvedHealingCredit(GameTestHelper h) throws Exception { super.aReplacementCastKeepsBaseSelectionConditionsAndResolvedHealingCredit(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:selected_ability_delay", maxTicks=15) @Override
    public void detachedRulesReadLaterSelectionsOnRealServerTicksAfterTheirSourceIsRemoved(GameTestHelper h) throws Exception { super.detachedRulesReadLaterSelectionsOnRealServerTicksAfterTheirSourceIsRemoved(h); }
}
