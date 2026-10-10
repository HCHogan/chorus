package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class AmplifiedGameTest extends com.imdomestic.chorus.test.AmplifiedGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:amplified_kills") @Override
    public void actualArcKillsUseObservedVictimWeightsAndProjectOnlyTheKiller(GameTestHelper h)throws Exception{super.actualArcKillsUseObservedVictimWeightsAndProjectOnlyTheKiller(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:amplified_sprint",maxTicks=140) @Override
    public void realTicksMaintainSpeedBoosterAfterAmplifiedThenRestoreSpeedAndJump(GameTestHelper h)throws Exception{super.realTicksMaintainSpeedBoosterAfterAmplifiedThenRestoreSpeedAndJump(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:amplified_resistance",maxTicks=75) @Override
    public void independentCombatantResistancesChangeActualHealthAndExcludeUnclassifiedDamage(GameTestHelper h)throws Exception{super.independentCombatantResistancesChangeActualHealthAndExcludeUnclassifiedDamage(h);}
}
