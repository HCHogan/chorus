package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SolarDamageScalingGameTest extends com.imdomestic.chorus.test.SolarDamageScalingGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:solar_damage_ignition",maxTicks=20) @Override
    public void physicalIncandescentKillsIgniteWithSurgeAndEachVictimsRadiantClassification(GameTestHelper h) throws Exception {super.physicalIncandescentKillsIgniteWithSurgeAndEachVictimsRadiantClassification(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:solar_damage_snapshot",maxTicks=30) @Override
    public void realScorchRetainsCapturedSurgeAndRadiantAfterChargeGearAndSourcesAreRemoved(GameTestHelper h) throws Exception {super.realScorchRetainsCapturedSurgeAndRadiantAfterChargeGearAndSourcesAreRemoved(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:solar_damage_perk",maxTicks=30) @Override
    public void actualRampageKillOnlyScalesSolarSnapshotsCreditedToThatWeapon(GameTestHelper h) throws Exception {super.actualRampageKillOnlyScalesSolarSnapshotsCreditedToThatWeapon(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:solar_damage_ability",maxTicks=20) @Override
    public void grenadeMeleeAndSuperIgnitionsDoNotReceiveTheEquippedWeaponBuffs(GameTestHelper h) throws Exception {super.grenadeMeleeAndSuperIgnitionsDoNotReceiveTheEquippedWeaponBuffs(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:solar_damage_unknown",maxTicks=20) @Override
    public void unknownScaledIgnitionKeepsCommittedHealthChargeAndLockoutWithoutReplay(GameTestHelper h) throws Exception {super.unknownScaledIgnitionKeepsCommittedHealthChargeAndLockoutWithoutReplay(h);}
}
