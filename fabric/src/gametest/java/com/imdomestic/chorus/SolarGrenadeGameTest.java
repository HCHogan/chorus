package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SolarGrenadeGameTest extends com.imdomestic.chorus.test.SolarGrenadeGameTest {
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_field",maxTicks=120) @Override
    public void paidPhysicalImpactCreatesFixedFieldWithThirteenRealPulsesAndExpiryScorch(GameTestHelper h) { super.paidPhysicalImpactCreatesFixedFieldWithThirteenRealPulsesAndExpiryScorch(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_impact",maxTicks=60) @Override
    public void physicalImpactUsesFractionalAshesAndSeparateImpactFalloff(GameTestHelper h) { super.physicalImpactUsesFractionalAshesAndSeparateImpactFalloff(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_guardian",maxTicks=65) @Override
    public void realGuardianReceivesOneThenSevenAndAHalfScorchFromTheField(GameTestHelper h) { super.realGuardianReceivesOneThenSevenAndAHalfScorchFromTheField(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_members",maxTicks=65) @Override
    public void fieldReselectsMovingEnemiesAndRejectsAllies(GameTestHelper h) { super.fieldReselectsMovingEnemiesAndRejectsAllies(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_overlap",maxTicks=65) @Override
    public void twoPaidPhysicalFieldsKeepSeparateLivesButShareTheTargetDotGate(GameTestHelper h) { super.twoPaidPhysicalFieldsKeepSeparateLivesButShareTheTargetDotGate(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_magma",maxTicks=100) @Override
    public void touchEmitsPhysicalMagmaThatBurstsWithoutFalloffAndKeepsGrenadeCredit(GameTestHelper h) { super.touchEmitsPhysicalMagmaThatBurstsWithoutFalloffAndKeepsGrenadeCredit(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_touch",maxTicks=155) @Override
    public void touchExtendsThePhysicalFieldAndKeepsEmittingAfterAspectRemoval(GameTestHelper h) { super.touchExtendsThePhysicalFieldAndKeepsEmittingAfterAspectRemoval(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_heat",maxTicks=70) @Override
    public void heatRisesConsumesThisRealGrenadeWithoutLaunchingOrCreatingAField(GameTestHelper h) { super.heatRisesConsumesThisRealGrenadeWithoutLaunchingOrCreatingAField(h); }
    @GameTest(structure="chorus_gametest:firebolt",environment="chorus_gametest:solar_grenade_unknown",maxTicks=65) @Override
    public void unknownNativeFieldHitPreservesCommittedHpAndCannotReplay(GameTestHelper h) { super.unknownNativeFieldHitPreservesCommittedHpAndCannotReplay(h); }
}
