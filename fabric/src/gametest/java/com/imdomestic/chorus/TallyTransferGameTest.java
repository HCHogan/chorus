package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class TallyTransferGameTest extends com.imdomestic.chorus.test.TallyTransferGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:tally_native_transfer", maxTicks = 30) @Override
    public void nativeTransferRecordsDestinationDamageAndClosesOriginalTally(GameTestHelper h) throws Exception { super.nativeTransferRecordsDestinationDamageAndClosesOriginalTally(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:tally_native_restore", maxTicks = 30) @Override
    public void jointArchiveRestoresIssuerLedgerAndMigratedPayoffWithoutReplayingDamage(GameTestHelper h) throws Exception { super.jointArchiveRestoresIssuerLedgerAndMigratedPayoffWithoutReplayingDamage(h); }
}
