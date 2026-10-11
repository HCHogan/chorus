package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ArmorChargeGameTest.*;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.finisher.Finisher;
import com.imdomestic.chorus.platform.minecraft.MinecraftDamageExecutor;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

public class ProximityWardGameTest {
    static EffectSource ward(String id,String holder){return new EffectSource(id,D+"proximity_ward",holder,new BuffInstance.Origin(holder,id,"",""),Set.of(),Map.of("receipt_seconds",new Measure(60,Unit.SECOND)));}
    static ProjectileGameTest.Harness openWard(GameTestHelper h)throws Exception{var t=ArmorFinisherGameTest.openMods(h,"proximity_ward");try{t.runtime.bind(ward("ward",owner(t)));return t;}catch(Exception|Error e){t.close();throw e;}}
    static double capacity(ProjectileGameTest.Harness t){return buff(t,"proximity_ward").orElseThrow().components().numbers().get("capacity");}
    static Finisher.Plan plan(ProjectileGameTest.Harness t){return Finisher.observe(state(t),owner(t),Optional.empty()).active();}
    static void hit(ProjectileGameTest.Harness t,LivingEntity attacker,double amount){t.owner.damageCooldownTime=0;MinecraftDamageExecutor.execute("ward/"+UUID.randomUUID(),t.owner,t.h.getLevel().damageSources().mobAttack(attacker),amount,false);ShieldThrowGameTest.settled(t);}
    static void cancel(ProjectileGameTest.Harness t,Finisher.Plan p){t.runtime.start(new RuleEngine.Signal("test:cancel",p.event()));ShieldThrowGameTest.settled(t);}
    @GameCase(environment="chorus_gametest:proximity_ward_damage",maxTicks=40)
    public void startShieldAbsorbsActualCombatantDamageWithoutVoidResistanceAndExpiresBeforeLaterDamage(GameTestHelper h)throws Exception{
        var t=openWard(h);try{var victim=ArmorFinisherGameTest.begin(t);var attacker=t.cow(8,40,8);near(h,capacity(t),200,"accepted shield");hit(t,attacker,150);near(h,capacity(t),50,"no borrowed Void resistance");near(h,t.owner.getHealth(),100,"shield absorbed the hit");h.assertTrue(buff(t,"proximity_ward",victim.getUUID().toString()).isEmpty(),"victim not protected by actor mod");
            t.finish(10,()->{h.assertTrue(!victim.isAlive()&&buff(t,"proximity_ward").isEmpty(),"finished phase has no remaining shield");hit(t,attacker,10);near(h,t.owner.getHealth(),90,"post-phase native hit reaches health");h.assertValueEqual(charges(t),0,"Ward neither grants nor spends Armor Charge");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:proximity_ward_break",maxTicks=40)
    public void brokenShieldCannotBeRefilledByDuplicateStartOrDuplicateModSource(GameTestHelper h)throws Exception{
        var t=openWard(h);try{t.runtime.bind(ward("duplicate",owner(t)));ArmorFinisherGameTest.begin(t);var p=plan(t);var attacker=t.cow(8,40,8);hit(t,attacker,250);near(h,t.owner.getHealth(),50,"excess damage reaches health");h.assertTrue(buff(t,"proximity_ward").isEmpty(),"broken shield removed");t.runtime.start(new RuleEngine.Signal("chorus:finisher_started",p.event()));ShieldThrowGameTest.settled(t);h.assertTrue(buff(t,"proximity_ward").isEmpty(),"same phase never refills");t.finish(10,()->h.assertValueEqual(FinisherGameTest.count(t,"completed"),1L,"shield break does not cancel accepted finisher"));}catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:proximity_ward_identity",maxTicks=40)
    public void cancellationRemovesOldShieldAndItsLateFactCannotRemoveTheNextExecutionShield(GameTestHelper h)throws Exception{
        var t=openWard(h);try{var victim=ArmorFinisherGameTest.begin(t);var old=plan(t);cancel(t,old);h.assertTrue(buff(t,"proximity_ward").isEmpty(),"cancel removed phase shield immediately");victim.discard();ArmorFinisherGameTest.begin(t);var b=buff(t,"proximity_ward").orElseThrow();t.runtime.start(new RuleEngine.Signal("chorus:finisher_cancelled",old.event()));t.runtime.start(new RuleEngine.Signal("chorus:finisher_started",old.event()));ShieldThrowGameTest.settled(t);h.assertValueEqual(buff(t,"proximity_ward").orElseThrow(),b,"old end/start cannot mutate new shield");t.finish(10,()->h.assertTrue(buff(t,"proximity_ward").isEmpty(),"new completion cleans its own instance"));}catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:proximity_ward_unbind",maxTicks=40)
    public void acceptedProtectionOutlivesSourceButTargetRemovalStillCancelsAndCleansIt(GameTestHelper h)throws Exception{
        var t=openWard(h);try{var victim=ArmorFinisherGameTest.begin(t);t.runtime.unbind("ward");near(h,capacity(t),200,"already accepted protection independent of mod source");victim.discard();t.finish(3,()->{h.assertTrue(buff(t,"proximity_ward").isEmpty(),"watchdog cancellation cleans after unbinding");h.assertValueEqual(FinisherGameTest.count(t,"completed"),0L,"no completion from disappearance");});}catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:proximity_ward_healthy",maxTicks=40)
    public void DuringPhaseWardAndHealthyFinisherCompletionAreIndependentAndCanCoexist(GameTestHelper h)throws Exception{
        var t=openWard(h);try{ArmorFinisherGameTest.charge(t,"healthy_finisher",1);ArmorFinisherGameTest.begin(t);hit(t,t.cow(8,40,8),250);near(h,t.owner.getHealth(),50,"wounded after shield overflow");h.assertValueEqual(charges(t),1,"Ward did not consume charge");t.finish(10,()->{near(h,t.owner.getHealth(),1000,"healthy reward on confirmed finish");h.assertValueEqual(charges(t),0,"one Healthy cost");h.assertTrue(buff(t,"proximity_ward").isEmpty(),"phase shield not retained as completion reward");});}catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:proximity_ward_death",maxTicks=40)
    public void actualActorDeathClearsWardImmediatelyAndWatchdogCancelsWithoutAPosthumousStrike(GameTestHelper h)throws Exception{
        var t=openWard(h);try{var victim=ArmorFinisherGameTest.begin(t);hit(t,t.cow(8,40,8),500);h.assertTrue(t.owner.isDeadOrDying()&&buff(t,"proximity_ward").isEmpty(),"real death cleans shield");t.finish(3,()->{h.assertTrue(victim.isAlive(),"dead actor cannot finish target");h.assertValueEqual(FinisherGameTest.count(t,"completed"),0L,"no completion reward");});}catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:proximity_ward_early_failure",maxTicks=40)
    public void earlierUnknownResultKeepsDiagnosticShieldStateButCannotProtectAgainstSubsequentNativeHits(GameTestHelper h)throws Exception{
        var t=openWard(h);try{ArmorFinisherGameTest.charge(t,"healthy_finisher",2);ArmorFinisherGameTest.begin(t);t.onCue=cue->{if(cue.cue().equals("test:armor_charge_consumed"))throw new IllegalStateException("Injected mid-finisher failure");};
            boolean failed=false;try{ArmorChargeGameTest.spend(t,1);}catch(IllegalStateException expected){h.assertValueEqual(expected.getMessage(),"Injected mid-finisher failure","expected fault");failed=true;}
            h.assertTrue(failed&&t.runtime.failure().isPresent(),"runtime stopped before phase deadline");near(h,capacity(t),200,"diagnostic domain still has committed shield");var attacker=t.cow(8,40,8);t.owner.damageCooldownTime=0;MinecraftDamageExecutor.execute("failed-ward/"+UUID.randomUUID(),t.owner,h.getLevel().damageSources().mobAttack(attacker),20,false);near(h,t.owner.getHealth(),80,"failed runtime supplies no shield to actual native damage");int operations=t.operations.size();
            h.runAfterDelay(10,()->{try{t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"no world replay or guessed completion");h.assertValueEqual(FinisherGameTest.count(t,"completed"),0L,"failed phase cannot award completion");near(h,capacity(t),200,"frozen diagnostic state is not silently expired");h.succeed();}finally{t.close();}});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:proximity_ward_unknown",maxTicks=40)
    public void finitePhaseDeadlineCannotLeaveAPermanentShieldAfterUnknownStrikeOutcome(GameTestHelper h)throws Exception{
        var t=openWard(h);try{var victim=ArmorFinisherGameTest.begin(t);t.failAfterDamage=true;h.runAfterDelay(10,()->{try{h.assertTrue(t.runtime.failure().isPresent()&&!victim.isAlive(),"unknown actual lethal strike receipt");h.assertTrue(buff(t,"proximity_ward").isEmpty(),"finite protection expired at phase deadline");h.assertValueEqual(FinisherGameTest.count(t,"completed"),0L,"no guessed completion");int n=t.operations.size();t.runtime.prepare();h.assertValueEqual(t.operations.size(),n,"no replay");h.succeed();}finally{t.close();}});}catch(Throwable e){t.close();throw e;}
    }
}
