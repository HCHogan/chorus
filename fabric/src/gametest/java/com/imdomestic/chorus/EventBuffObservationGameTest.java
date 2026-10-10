package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EventBuffObservationGameTest extends com.imdomestic.chorus.test.EventBuffObservationGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void managedAndOrdinaryNativeDeathPreserveMarkedVictimAfterCleanup(GameTestHelper h) throws Exception {super.managedAndOrdinaryNativeDeathPreserveMarkedVictimAfterCleanup(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void earlierHitReactionCannotInventABuffForTheSameDeathsKillCondition(GameTestHelper h) throws Exception {super.earlierHitReactionCannotInventABuffForTheSameDeathsKillCondition(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void oldNativeHitDoesNotBelongToARecreatedBuffWithTheSameLogicalKey(GameTestHelper h) throws Exception {super.oldNativeHitDoesNotBelongToARecreatedBuffWithTheSameLogicalKey(h);}

}
