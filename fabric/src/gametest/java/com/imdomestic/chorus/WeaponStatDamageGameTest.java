package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponStatDamageGameTest extends com.imdomestic.chorus.test.WeaponStatDamageGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_ranks",maxTicks=20) @Override
    public void threeAmmoOriginsUsePhysicalArmorAndObservedRanksWithoutGuessingUnknownTargets(GameTestHelper h)throws Exception {super.threeAmmoOriginsUsePhysicalArmorAndObservedRanksWithoutGuessingUnknownTargets(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_snapshot",maxTicks=20) @Override
    public void inFlightWeaponKeepsLaunchStatAndAmmoButReadsTheVictimsCurrentRank(GameTestHelper h)throws Exception {super.inFlightWeaponKeepsLaunchStatAndAmmoButReadsTheVictimsCurrentRank(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_fonts",maxTicks=30) @Override
    public void actualFontSurgeRadiantAndRampageComposeAndSpendingOnlyChangesFutureShots(GameTestHelper h)throws Exception {super.actualFontSurgeRadiantAndRampageComposeAndSpendingOnlyChangesFutureShots(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_guardian",maxTicks=20) @Override
    public void actualGuardianUsesItsOwnBracketEvenInPveAndWithConflictingRankTags(GameTestHelper h)throws Exception {super.actualGuardianUsesItsOwnBracketEvenInPveAndWithConflictingRankTags(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_ignition",maxTicks=20) @Override
    public void physicalIncandescentRetainsAmmoAndInitialStatForEachIgnitionRecipient(GameTestHelper h)throws Exception {super.physicalIncandescentRetainsAmmoAndInitialStatForEachIgnitionRecipient(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_scorch",maxTicks=30) @Override
    public void actualScorchTickKeepsInitialWeaponsStatAfterArmorAndProviderRemoval(GameTestHelper h)throws Exception {super.actualScorchTickKeepsInitialWeaponsStatAfterArmorAndProviderRemoval(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:weapon_stat_unknown",maxTicks=20) @Override
    public void unknownStatScaledImpactKeepsHealthAndAmmoWithoutReplay(GameTestHelper h)throws Exception {super.unknownStatScaledImpactKeepsHealthAndAmmoWithoutReplay(h);}
}
