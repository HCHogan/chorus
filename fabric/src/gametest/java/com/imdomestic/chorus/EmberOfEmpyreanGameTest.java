package com.imdomestic.chorus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
public class EmberOfEmpyreanGameTest extends com.imdomestic.chorus.test.EmberOfEmpyreanGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void solarWeaponKillsExtendBothEffectsAndSubsequentMercyPickupOnlyExtendsRestoration(GameTestHelper h)throws Exception{super.solarWeaponKillsExtendBothEffectsAndSubsequentMercyPickupOnlyExtendsRestoration(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void longTimersAreCappedButUnknownRanksLeaveThemUnchangedAndReportClassificationGap(GameTestHelper h)throws Exception{super.longTimersAreCappedButUnknownRanksLeaveThemUnchangedAndReportClassificationGap(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:empyrean_dot",maxTicks=40) @Override
    public void actualScorchPeriodicDeathExtendsBothStatesForItsAttributedKiller(GameTestHelper h)throws Exception{super.actualScorchPeriodicDeathExtendsBothStatesForItsAttributedKiller(h);}
}
