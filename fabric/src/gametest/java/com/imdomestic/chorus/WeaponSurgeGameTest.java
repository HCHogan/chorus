package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class WeaponSurgeGameTest extends com.imdomestic.chorus.test.WeaponSurgeGameTest {
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_surge_elements", maxTicks=20) @Override
    public void allSixElementWeaponsUseActualOrbChargeAndRejectNonmatchingSurges(GameTestHelper h) throws Exception { super.allSixElementWeaponsUseActualOrbChargeAndRejectNonmatchingSurges(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_surge_switch", maxTicks=20) @Override
    public void actualWeaponSwitchReadsEachElementsOwnCopyTierFromTheSharedPool(GameTestHelper h) throws Exception { super.actualWeaponSwitchReadsEachElementsOwnCopyTierFromTheSharedPool(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_surge_rampage", maxTicks=50) @Override
    public void actualRampageKillAndRadiantMultiplyWithOnlyTheHighestActiveSurge(GameTestHelper h) throws Exception { super.actualRampageKillAndRadiantMultiplyWithOnlyTheHighestActiveSurge(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_surge_masked", maxTicks=270) @Override
    public void higherTierDoesNotPauseLowerArmorChargeDecayAndExpiresIndependently(GameTestHelper h) throws Exception { super.higherTierDoesNotPauseLowerArmorChargeDecayAndExpiresIndependently(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_surge_dilation", maxTicks=440) @Override
    public void timeDilationAndFontsShareOneRealDecayWithoutChangingTheSurgeDamageTier(GameTestHelper h) throws Exception { super.timeDilationAndFontsShareOneRealDecayWithoutChangingTheSurgeDamageTier(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_surge_kickstart", maxTicks=20) @Override
    public void paidKickstartConsumesThePoolButCannotRewriteAnEarlierPhysicalShot(GameTestHelper h) throws Exception { super.paidKickstartConsumesThePoolButCannotRewriteAnEarlierPhysicalShot(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_surge_unbind", maxTicks=20) @Override
    public void removingCopiesChangesOnlyFutureDamageAndKeepsOtherPassiveBenefitsAndTheirDeadline(GameTestHelper h) throws Exception { super.removingCopiesChangesOnlyFutureDamageAndKeepsOtherPassiveBenefitsAndTheirDeadline(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:weapon_surge_unknown", maxTicks=20) @Override
    public void unknownPhysicalImpactRetainsBoostedDamageAndSpentAmmoWithoutReplayingTheShot(GameTestHelper h) throws Exception { super.unknownPhysicalImpactRetainsBoostedDamageAndSpentAmmoWithoutReplayingTheShot(h); }
}
