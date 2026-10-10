package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;

/** Explicit synthetic native classification exercises the real damage adapter; no held-item inference. */
public class FreezeDamageGameTest {
    static DamageReceipt hit(FreezeGameTest.Harness t,LivingEntity target,double amount,String...tags){t.nativeTags=Set.of(tags);target.damageCooldownTime=0;return MinecraftDamageExecutor.execute("freeze-damage/"+UUID.randomUUID(),target,t.h.getLevel().damageSources().mobAttack((LivingEntity)t.actors.getFirst()),amount,false);}
    @GameCase(environment="chorus_gametest:freeze_damage")
    public void actualClassifiedWeaponAndAbilityDamageUsesFrozenTargetFactorsAndClearsImmediately(GameTestHelper h){
        for(var mode:EffectState.Mode.values())try(var t=new FreezeGameTest.Harness(h,FreezeGameTest.program(),EffectState.empty().withMode(mode))){
            var source=t.caster();var target=t.mob(EntityTypes.COW,2,2,"elite");t.apply(source,target);
            near(h,hit(t,target,10,"chorus:weapon_damage","chorus:primary_ammo").healthLoss(),mode==EffectState.Mode.PVP?4:9.5,"primary freeze factor");
            near(h,hit(t,target,10,"chorus:weapon_damage","chorus:special_ammo").healthLoss(),11,"special freeze factor");near(h,hit(t,target,10,"chorus:ability_damage","chorus:solar").healthLoss(),10.5,"light ability factor");
            t.clear(source,target);near(h,hit(t,target,10,"chorus:weapon_damage","chorus:special_ammo").healthLoss(),10,"cleared target still modified incoming damage");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:freeze_scaled_threshold")
    public void scaledRealMeleeLossTriggersShatterWhileBossReceivesNoMinorMeleeBonus(GameTestHelper h){
        try(var t=new FreezeGameTest.Harness(h)){
            var source=t.caster();var ordinary=t.mob(EntityTypes.COW,2,2,"elite");var boss=t.mob(EntityTypes.COW,2,12,"boss");t.apply(source,ordinary);t.apply(source,boss);
            var first=hit(t,ordinary,50,"chorus:melee_damage","chorus:basic_melee");near(h,first.healthLoss(),110,"scaled actual melee receipt");near(h,ordinary.getHealth(),529,"threshold uses scaled loss then one shatter");h.assertTrue(t.status(ordinary).isEmpty(),"scaled threshold failed to shatter");h.assertValueEqual(t.damage.size(),1,"one shatter command");
            near(h,hit(t,boss,50,"chorus:melee_damage","chorus:basic_melee").healthLoss(),50,"boss incorrectly received minor bonus");h.assertTrue(t.status(boss).isPresent(),"boss shattered below threshold");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:freeze_damage_snapshot")
    public void capturedDamageUsesLiveFreezeAtImpactAndPreservesNativeCredit(GameTestHelper h){
        try(var t=new FreezeGameTest.Harness(h)){
            var source=t.caster();var target=t.mob(EntityTypes.COW,2,2,"elite");var command=new DamageCommand(target.getUUID().toString(),source.origin(),10,"test:snapshot",Set.of("chorus:weapon_damage","chorus:special_ammo"),Set.of(),false,Optional.of("chorus_d2:outgoing"));var snapshot=t.runtime.program().captureDamage(t.runtime.state().engine().domain(),command);t.apply(source,target);
            var request=new com.imdomestic.chorus.rule.RuleEngine.WorldRequest(new com.imdomestic.chorus.rule.RuleEngine.OperationId(900,0,0),snapshot.command(target.getUUID().toString()));
            var result=(DamageReceipt)t.world.apply(request);near(h,result.healthLoss(),11,"freeze added after capture must affect impact");h.assertValueEqual(target.getLastDamageSource().getEntity(),t.actors.getFirst(),"target contribution transferred kill credit");t.clear(source,target);t.settled();
        }h.succeed();
    }
}
