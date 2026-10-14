package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class IncineratorSnapGameTest extends com.imdomestic.chorus.test.IncineratorSnapGameTest {
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_fan",maxTicks=60) @Override
    public void physicalFanKeepsFiveDirectionsWhileLookingUpDownOrHorizontally(GameTestHelper h) { super.physicalFanKeepsFiveDirectionsWhileLookingUpDownOrHorizontally(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_ignite",maxTicks=80) @Override
    public void fiveActualProximityExplosionsAccumulateScorchAndProduceSharedIgnition(GameTestHelper h) { super.fiveActualProximityExplosionsAccumulateScorchAndProduceSharedIgnition(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_scorch_kill",maxTicks=80) @Override
    public void actualScorchKillArmsSunbracersAndPaidSolarGrenadeConsumesReady(GameTestHelper h) { super.actualScorchKillArmsSunbracersAndPaidSolarGrenadeConsumesReady(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_sunbracers",maxTicks=120) @Override
    public void actualWarlockMeleeKillFundsRepeatedPaidSolarGrenadesThroughSunbracers(GameTestHelper h) { super.actualWarlockMeleeKillFundsRepeatedPaidSolarGrenadesThroughSunbracers(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_snapshot",maxTicks=60) @Override
    public void acceptedSparkRetainsAshesAndSourceAfterRemovingTheMeleeSelection(GameTestHelper h) { super.acceptedSparkRetainsAshesAndSourceAfterRemovingTheMeleeSelection(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_geometry",maxTicks=80) @Override
    public void rangeAndPhysicalWallBothTriggerExplosionAtTheirObservedPoint(GameTestHelper h) { super.rangeAndPhysicalWallBothTriggerExplosionAtTheirObservedPoint(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_immune",maxTicks=80) @Override
    public void nativeImmunityAndStatusDenialDoNotCreateScorchOrSunbracers(GameTestHelper h) { super.nativeImmunityAndStatusDenialDoNotCreateScorchOrSunbracers(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:snap_unknown",maxTicks=60) @Override
    public void unknownNativeKillConsumesSparkWithoutInventingConfirmedKillRewards(GameTestHelper h) { super.unknownNativeKillConsumesSparkWithoutInventingConfirmedKillRewards(h); }
}
