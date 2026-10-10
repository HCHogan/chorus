package com.imdomestic.chorus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
public class EventPositionObservationGameTest extends com.imdomestic.chorus.test.EventPositionObservationGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void nativeAndManagedReceiptsRetainAllAnchorsAfterDeathReactionMovesAndRemovesVictim(GameTestHelper h)throws Exception{super.nativeAndManagedReceiptsRetainAllAnchorsAfterDeathReactionMovesAndRemovesVictim(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:event_position",maxTicks=40) @Override
    public void detachedBlastKeepsReceiptPositionAfterVictimAndEquipmentSourceAreRemoved(GameTestHelper h)throws Exception{super.detachedBlastKeepsReceiptPositionAfterVictimAndEquipmentSourceAreRemoved(h);}
}
