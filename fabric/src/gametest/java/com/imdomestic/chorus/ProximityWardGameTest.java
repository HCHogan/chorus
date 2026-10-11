package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProximityWardGameTest extends com.imdomestic.chorus.test.ProximityWardGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:proximity_ward_early_failure", maxTicks=40) @Override
    public void earlierUnknownResultKeepsDiagnosticShieldStateButCannotProtectAgainstSubsequentNativeHits(GameTestHelper h) throws Exception { super.earlierUnknownResultKeepsDiagnosticShieldStateButCannotProtectAgainstSubsequentNativeHits(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:proximity_ward_damage", maxTicks=40) @Override
    public void startShieldAbsorbsActualCombatantDamageWithoutVoidResistanceAndExpiresBeforeLaterDamage(GameTestHelper h) throws Exception { super.startShieldAbsorbsActualCombatantDamageWithoutVoidResistanceAndExpiresBeforeLaterDamage(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:proximity_ward_break", maxTicks=40) @Override
    public void brokenShieldCannotBeRefilledByDuplicateStartOrDuplicateModSource(GameTestHelper h) throws Exception { super.brokenShieldCannotBeRefilledByDuplicateStartOrDuplicateModSource(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:proximity_ward_identity", maxTicks=40) @Override
    public void cancellationRemovesOldShieldAndItsLateFactCannotRemoveTheNextExecutionShield(GameTestHelper h) throws Exception { super.cancellationRemovesOldShieldAndItsLateFactCannotRemoveTheNextExecutionShield(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:proximity_ward_unbind", maxTicks=40) @Override
    public void acceptedProtectionOutlivesSourceButTargetRemovalStillCancelsAndCleansIt(GameTestHelper h) throws Exception { super.acceptedProtectionOutlivesSourceButTargetRemovalStillCancelsAndCleansIt(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:proximity_ward_healthy", maxTicks=40) @Override
    public void DuringPhaseWardAndHealthyFinisherCompletionAreIndependentAndCanCoexist(GameTestHelper h) throws Exception { super.DuringPhaseWardAndHealthyFinisherCompletionAreIndependentAndCanCoexist(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:proximity_ward_death", maxTicks=40) @Override
    public void actualActorDeathClearsWardImmediatelyAndWatchdogCancelsWithoutAPosthumousStrike(GameTestHelper h) throws Exception { super.actualActorDeathClearsWardImmediatelyAndWatchdogCancelsWithoutAPosthumousStrike(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:proximity_ward_unknown", maxTicks=40) @Override
    public void finitePhaseDeadlineCannotLeaveAPermanentShieldAfterUnknownStrikeOutcome(GameTestHelper h) throws Exception { super.finitePhaseDeadlineCannotLeaveAPermanentShieldAfterUnknownStrikeOutcome(h); }
}
