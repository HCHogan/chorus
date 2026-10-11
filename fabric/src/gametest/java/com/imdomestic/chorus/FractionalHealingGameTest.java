package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FractionalHealingGameTest extends com.imdomestic.chorus.test.FractionalHealingGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void percentageSamplesCurrentMaximumAndReportsResolvedRequestActualHealingAndOverflow(GameTestHelper h) { super.percentageSamplesCurrentMaximumAndReportsResolvedRequestActualHealingAndOverflow(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void absentTargetsKeepFractionalAmountUnresolvedAndDeadTargetsNeverRevive(GameTestHelper h) { super.absentTargetsKeepFractionalAmountUnresolvedAndDeadTargetsNeverRevive(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void mixedRecoverySelectsByResolvedRateAndRetainsWinningSourceAcrossMaximumChangesAndExpiry(GameTestHelper h) { super.mixedRecoverySelectsByResolvedRateAndRetainsWinningSourceAcrossMaximumChangesAndExpiry(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void operationLedgerReusesOriginalMaximumAndUnknownHealingNeverReexecutes(GameTestHelper h) { super.operationLedgerReusesOriginalMaximumAndUnknownHealingNeverReexecutes(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:fraction_recovery", maxTicks = 25) @Override
    public void actualTicksPreserveRegenerationOnShieldOnlyDamageAndRestartAfterHealthDamage(GameTestHelper h) { super.actualTicksPreserveRegenerationOnShieldOnlyDamageAndRestartAfterHealthDamage(h); }
}
