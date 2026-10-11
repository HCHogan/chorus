package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class LinkedRechargeGameTest extends com.imdomestic.chorus.test.LinkedRechargeGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void actualAbilityCannotSpendPartialCycleAndAFullGrantRestoresBothUses(GameTestHelper h) { super.actualAbilityCannotSpendPartialCycleAndAFullGrantRestoresBothUses(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:linked_recharge_ticks", maxTicks=55) @Override
    public void realTicksRestoreBothUsesAtOneCycleBoundaryDespiteASecondUseMidway(GameTestHelper h) { super.realTicksRestoreBothUsesAtOneCycleBoundaryDespiteASecondUseMidway(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownNativeObservationRetainsBothAccountWritesAndDoesNotReplay(GameTestHelper h) { super.unknownNativeObservationRetainsBothAccountWritesAndDoesNotReplay(h); }
}
