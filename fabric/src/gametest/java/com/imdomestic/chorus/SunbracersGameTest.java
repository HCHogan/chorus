package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SunbracersGameTest extends com.imdomestic.chorus.test.SunbracersGameTest {
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_cycle",maxTicks=120) @Override
    public void actualPoweredKillArmsAndEveryPhysicalThrowPaysFromTheTimedRegeneration(GameTestHelper h) { super.actualPoweredKillArmsAndEveryPhysicalThrowPaysFromTheTimedRegeneration(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_passive",maxTicks=200) @Override
    public void capturedFastballAndTouchLifetimePersistAfterUnequippingBothSources(GameTestHelper h) { super.capturedFastballAndTouchLifetimePersistAfterUnequippingBothSources(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_heat",maxTicks=70) @Override
    public void heatRisesUsesTheSelectedSolarCostAndActivatesTheSameRegenerationWithoutThrowing(GameTestHelper h) { super.heatRisesUsesTheSelectedSolarCostAndActivatesTheSameRegenerationWithoutThrowing(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_grenade_kill",maxTicks=60) @Override
    public void actualSolarGrenadeKillCannotSubstituteForAPoweredMeleeKill(GameTestHelper h) { super.actualSolarGrenadeKillCannotSubstituteForAPoweredMeleeKill(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_detach",maxTicks=70) @Override
    public void removingTheExoticSplitsActualRechargeWithoutDeletingAlreadyAcceptedFields(GameTestHelper h) { super.removingTheExoticSplitsActualRechargeWithoutDeletingAlreadyAcceptedFields(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_unknown",maxTicks=60) @Override
    public void unknownNativePoweredKillKeepsItsWorldDeathButCannotInventReadyOrReplay(GameTestHelper h) { super.unknownNativePoweredKillKeepsItsWorldDeathButCannotInventReadyOrReplay(h); }
}
