package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class NativeMeleeGameTest extends com.imdomestic.chorus.test.NativeMeleeGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_melee_methods") @Override
    public void ordinaryMobAttacksAndAllDamagingOverridesAreRejectedBeforeSideEffects(GameTestHelper h){super.ordinaryMobAttacksAndAllDamagingOverridesAreRejectedBeforeSideEffects(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_melee_player") @Override
    public void ordinaryPlayerAttackPacketsPreserveCooldownAndWeaponDurabilityWhenDenied(GameTestHelper h)throws Exception{super.ordinaryPlayerAttackPacketsPreserveCooldownAndWeaponDurabilityWhenDenied(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_melee_stab") @Override
    public void stabAttackCannotBypassTheGateWithKnockbackOrDismountOnly(GameTestHelper h){super.stabAttackCannotBypassTheGateWithKnockbackOrDismountOnly(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_melee_cube") @Override
    public void slimeAndMagmaCubeContactCannotBypassMeleeRestrictions(GameTestHelper h){super.slimeAndMagmaCubeContactCannotBypassMeleeRestrictions(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_melee_dragon") @Override
    public void dragonContactRestrictionsCoverWingPushAndEveryVictim(GameTestHelper h)throws Exception{super.dragonContactRestrictionsCoverWingPushAndEveryVictim(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_melee_beam") @Override
    public void guardiansPhysicalBeamComponentRemainsARangedAttack(GameTestHelper h)throws Exception{super.guardiansPhysicalBeamComponentRemainsARangedAttack(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_melee_expiry",maxTicks=18) @Override
    public void recipientMeleeRestrictionExpiresAndKeepsItsCasterIdentity(GameTestHelper h){super.recipientMeleeRestrictionExpiresAndKeepsItsCasterIdentity(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:native_melee_ai",maxTicks=110) @Override
    public void autonomousZombieKeepsOtherAiAndResumesMeleeAfterRestrictionRemoval(GameTestHelper h){super.autonomousZombieKeepsOtherAiAndResumesMeleeAfterRestrictionRemoval(h);}
}
