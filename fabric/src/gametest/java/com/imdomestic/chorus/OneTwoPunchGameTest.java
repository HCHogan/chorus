package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class OneTwoPunchGameTest extends com.imdomestic.chorus.test.OneTwoPunchGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:one_two_punch_split", maxTicks = 25) @Override
    public void declaredSingleMeleeAttackSharesTheBuffAcrossTwoActualComponents(GameTestHelper h) throws Exception { super.declaredSingleMeleeAttackSharesTheBuffAcrossTwoActualComponents(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:one_two_punch_shotgun", maxTicks = 25) @Override
    public void ownedShotgunPelletsArmOneActualMeleeWhileGrenadeAndSecondStrikeUseBaseDamage(GameTestHelper h) throws Exception { super.ownedShotgunPelletsArmOneActualMeleeWhileGrenadeAndSecondStrikeUseBaseDamage(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:one_two_punch_enhanced", maxTicks = 40) @Override
    public void enhancedHandCannonArmsAtTenWhileTwoPelletsStillFlyAndUsesPvpMeleeBonus(GameTestHelper h) throws Exception { super.enhancedHandCannonArmsAtTenWhileTwoPelletsStillFlyAndUsesPvpMeleeBonus(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:one_two_punch_stow", maxTicks = 40) @Override
    public void stowPreventsLateArmingAndRemovesAnAlreadyArmedOtherWeapon(GameTestHelper h) throws Exception { super.stowPreventsLateArmingAndRemovesAnAlreadyArmedOtherWeapon(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:one_two_punch_expiry", maxTicks = 95) @Override
    public void realTicksExpireTheThreeSecondBuffBeforeTheNextMelee(GameTestHelper h) throws Exception { super.realTicksExpireTheThreeSecondBuffBeforeTheNextMelee(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:one_two_punch_unknown", maxTicks = 25) @Override
    public void unknownBoostedMeleeReceiptStopsBeforeSecondStrikeWithoutReplaying(GameTestHelper h) throws Exception { super.unknownBoostedMeleeReceiptStopsBeforeSecondStrikeWithoutReplaying(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:one_two_freeze",maxTicks=35) @Override
    public void actualPelletsAndFrozenVictimUseOneSharedMeleeMaximumAndConsumeOnlyThePerk(GameTestHelper h)throws Exception{super.actualPelletsAndFrozenVictimUseOneSharedMeleeMaximumAndConsumeOnlyThePerk(h);}
}
