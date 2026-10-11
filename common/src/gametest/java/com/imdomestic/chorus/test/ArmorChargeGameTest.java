package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.NumericQuery;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** Physical Orb contacts and server time drive one shared Armor Charge pool and live source contributions. */
public class ArmorChargeGameTest {
    static final String D="chorus_d2:";
    static EffectSource system(String holder){return new EffectSource("armor_charge/"+holder,D+"armor_charge_system",holder,new BuffInstance.Origin(holder,"armor_charge/"+holder,"",""),Set.of(),Map.of("receipt_seconds",new Measure(60,Unit.SECOND)));}
    static EffectSource mod(String holder,String name,int copy){String id=name+"/"+copy+"/"+holder;return new EffectSource(id,D+name,holder,new BuffInstance.Origin(holder,id,"",""),Set.of());}
    static ProjectileGameTest.Harness open(GameTestHelper h)throws Exception{
        var t=StarvationGameTest.open(h,false,"armor_charge","armor_charge_mods","armor_charge_inputs");
        try{t.runtime.unbind("starvation");t.runtime.bind(system(owner(t)));IncandescentGameTest.bind(t,"armor_inputs","test:armor_charge_inputs","");return t;}catch(Exception|Error e){t.close();throw e;}
    }
    static void mod(ProjectileGameTest.Harness t,String name,int copy){t.runtime.bind(mod(owner(t),name,copy));ShieldThrowGameTest.settled(t);}
    static void unmod(ProjectileGameTest.Harness t,String name,int copy){t.runtime.unbind(name+"/"+copy+"/"+owner(t));ShieldThrowGameTest.settled(t);}
    static Optional<BuffInstance> buff(ProjectileGameTest.Harness t,String name,String holder){return state(t).buffs().instances().values().stream().filter(b->b.key().holder().equals(holder)&&b.key().definition().equals(D+name)).findFirst();}
    static Optional<BuffInstance> buff(ProjectileGameTest.Harness t,String name){return buff(t,name,owner(t));}
    static int charges(ProjectileGameTest.Harness t){return buff(t,"armor_charge").map(BuffInstance::count).orElse(0);}
    static EffectObject spawn(ProjectileGameTest.Harness t){StarvationGameTest.spawn(t,"orb_of_power",t.owner);return t.pickups.getLast();}
    static void orb(ProjectileGameTest.Harness t){StarvationGameTest.collect(t,spawn(t));}
    static void action(ProjectileGameTest.Harness t,String kind,String target,int stacks,int bonus){t.runtime.start(new RuleEngine.Signal("test:"+kind+"_armor_charge",new EffectEvent(owner(t),target,new BuffInstance.Origin(owner(t),"input","",""),Set.of(),Map.of("stacks",new Measure(stacks,Unit.COUNT),"bonus",new Measure(bonus,Unit.COUNT)))));ShieldThrowGameTest.settled(t);}
    static void spend(ProjectileGameTest.Harness t,int n){action(t,"spend",owner(t),n,0);}
    static double stat(ProjectileGameTest.Harness t,String name){return t.runtime.program().attribute(state(t),owner(t),D+name+"_stat",new Measure(50,Unit.STAT_POINT),new NumericQuery.Path(Set.of(),Set.of())).output().value();}
    @GameCase(environment="chorus_gametest:armor_charge_pickup")
    public void actualOrbContactsUseThreeToSixCapacityAndStacksOnStacksWithoutDuplicatingSuperEnergy(GameTestHelper h)throws Exception{
        try(var t=open(h)){StarvationGameTest.fill(t,"super");var waiting=spawn(t);t.owner.setPos(waiting.position());waiting.tick();h.assertTrue(!waiting.isRemoved(),"no charge mod leaves full-Super Orb");
            mod(t,"charged_up",0);mod(t,"stacks_on_stacks",0);mod(t,"stacks_on_stacks",1);StarvationGameTest.collect(t,waiting);h.assertValueEqual(charges(t),2,"one bonus despite duplicate Stacks on Stacks");orb(t);h.assertValueEqual(charges(t),4,"current four-stack capacity");var full=spawn(t);t.owner.setPos(full.position());full.tick();h.assertTrue(!full.isRemoved(),"both charge and Super are full");mod(t,"charged_up",1);StarvationGameTest.collect(t,full);h.assertValueEqual(charges(t),5,"late capacity allows already-spawned Orb");mod(t,"charged_up",2);orb(t);h.assertValueEqual(charges(t),6,"six maximum");near(h,StarvationGameTest.account(t,"super"),1,"full Super is neither spent nor overfilled");
            unmod(t,"charged_up",2);h.assertValueEqual(charges(t),5,"current capacity reduction clamps pool");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:armor_charge_fonts")
    public void allSixFontContributionsFollowActualPickupAndLiveModCopiesThenDisappearOnSpend(GameTestHelper h)throws Exception{
        try(var t=open(h)){for(String name:List.of("health","class","grenade","melee","super","weapons")){mod(t,name+"_font",0);near(h,stat(t,name),50,"inactive without charge");}orb(t);
            for(String name:List.of("health","class","grenade","melee","super","weapons")){near(h,stat(t,name),70,"one copy");mod(t,name+"_font",1);near(h,stat(t,name),90,"two copies");mod(t,name+"_font",2);near(h,stat(t,name),100,"three copies");unmod(t,name+"_font",1);near(h,stat(t,name),90,"live removal");}
            spend(t,1);for(String name:List.of("health","class","grenade","melee","super","weapons"))near(h,stat(t,name),50,"last charge consumed");h.assertTrue(buff(t,"armor_charge_decay").isEmpty(),"no empty-pool timer");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:armor_charge_decay",maxTicks=640)
    public void realServerTicksDecayOneStackEveryTenSecondsAndNewOrbDoesNotResetTheRunningTimer(GameTestHelper h)throws Exception{
        var t=open(h);try{mod(t,"grenade_font",0);orb(t);long first=buff(t,"armor_charge_decay").orElseThrow().deadline();
            RampageGameTest.at(t,180,()->{orb(t);orb(t);h.assertValueEqual(charges(t),3,"new physical pickups");h.assertValueEqual(buff(t,"armor_charge_decay").orElseThrow().deadline(),first,"new gains do not refresh timer");});
            RampageGameTest.at(t,202,()->h.assertValueEqual(charges(t),2,"one stack at first deadline"));
            t.finish(602,()->{h.assertValueEqual(charges(t),0,"three consecutive ten-second decays");near(h,stat(t,"grenade"),50,"last decay removes passive benefit");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:armor_charge_dilation",maxTicks=440)
    public void threeTimeDilationCopiesUseActualTwentySecondDecayRegardlessOfFontCopyCount(GameTestHelper h)throws Exception{
        var t=open(h);try{for(int n=0;n<3;n++){mod(t,"time_dilation",n);mod(t,"health_font",n);}orb(t);orb(t);
            RampageGameTest.at(t,202,()->h.assertValueEqual(charges(t),2,"not the default ten seconds"));t.finish(402,()->{h.assertValueEqual(charges(t),1,"one stack after twenty seconds");near(h,stat(t,"health"),100,"three-copy Font remains while one charge exists");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:armor_charge_spend",maxTicks=330)
    public void spendingRestartsDecayAndInsufficientCostCannotMoveItsDeadline(GameTestHelper h)throws Exception{
        var t=open(h);try{mod(t,"class_font",0);orb(t);orb(t);orb(t);long first=buff(t,"armor_charge_decay").orElseThrow().deadline();
            RampageGameTest.at(t,80,()->{spend(t,4);h.assertValueEqual(buff(t,"armor_charge_decay").orElseThrow().deadline(),first,"failed spend retains deadline");spend(t,1);h.assertValueEqual(charges(t),2,"one paid stack");h.assertValueEqual(buff(t,"armor_charge_decay").orElseThrow().deadline(),state(t).buffs().timeMicros()+10_000_000,"full interval after payment");});
            RampageGameTest.at(t,202,()->h.assertValueEqual(charges(t),2,"old deadline cannot decay a replacement"));t.finish(282,()->h.assertValueEqual(charges(t),1,"new deadline decays once"));
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:armor_charge_unbind",maxTicks=440)
    public void removingTheLastPassiveStopsDecayWhileNonPassiveModsKeepThePool(GameTestHelper h)throws Exception{
        var t=open(h);try{mod(t,"charged_up",0);mod(t,"health_font",0);orb(t);orb(t);unmod(t,"health_font",0);h.assertTrue(buff(t,"armor_charge_decay").isEmpty(),"no passive mod no decay");
            t.finish(402,()->{h.assertValueEqual(charges(t),2,"charges persist without passive mods");mod(t,"health_font",0);h.assertValueEqual(buff(t,"armor_charge_decay").orElseThrow().deadline(),state(t).buffs().timeMicros()+10_000_000,"new passive interval");unmod(t,"charged_up",0);unmod(t,"health_font",0);h.assertValueEqual(charges(t),0,"last mod removal clears pool");h.assertTrue(buff(t,"armor_charge_pickup_permission").isEmpty(),"last removal revokes only Armor Charge permission");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:armor_charge_starvation")
    public void independentStarvationPermissionStillAllowsAFullChargeOrbAndOnlyItsOwnReward(GameTestHelper h)throws Exception{
        try(var t=open(h)){mod(t,"grenade_font",0);orb(t);orb(t);orb(t);StarvationGameTest.fill(t,"super");var waiting=spawn(t);t.owner.setPos(waiting.position());waiting.tick();h.assertTrue(!waiting.isRemoved(),"charge capacity denies its own permission");
            t.runtime.bind(StarvationGameTest.fragment(t,"starvation",10));StarvationGameTest.collect(t,waiting);h.assertValueEqual(charges(t),3,"no extra charge at full capacity");near(h,t.owner.getHealth(),170,"independent Devour activation");near(h,StarvationGameTest.account(t,"super"),1,"no overflow Super");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:armor_charge_death")
    public void actualPlayerDeathClearsChargesAndRejectsSubsequentGainUntilAlive(GameTestHelper h)throws Exception{
        try(var t=open(h)){mod(t,"health_font",0);orb(t);var attacker=t.cow(7.5,40,3.5);MinecraftDamageExecutor.execute("armor_charge_death/"+UUID.randomUUID(),t.owner,h.getLevel().damageSources().mobAttack(attacker),2000,false);ShieldThrowGameTest.settled(t);
            h.assertTrue(t.owner.isDeadOrDying(),"confirmed actual death");h.assertValueEqual(charges(t),0,"cleared on actual death");near(h,stat(t,"health"),50,"dead owner has no charged Font");action(t,"gain",owner(t),1,1);h.assertValueEqual(charges(t),0,"dead recipient rejected");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:armor_charge_unknown")
    public void unknownReactionAfterPaymentRetainsConsumedStacksWithoutReplayingTheCost(GameTestHelper h)throws Exception{
        try(var t=open(h)){mod(t,"health_font",0);orb(t);orb(t);orb(t);t.onCue=cue->{if(cue.cue().equals("test:armor_charge_consumed"))throw new IllegalStateException("Injected unknown consumption reaction");};
            boolean threw=false;
            try{t.runtime.start(new RuleEngine.Signal("test:spend_armor_charge",new EffectEvent(owner(t),owner(t),new BuffInstance.Origin(owner(t),"input","",""),Set.of(),Map.of("stacks",new Measure(1,Unit.COUNT)))));}
            catch(IllegalStateException expected){h.assertValueEqual(expected.getMessage(),"Injected unknown consumption reaction","expected fault propagated");threw=true;}
            h.assertTrue(threw,"explicit start reports unknown world result");
            h.assertTrue(t.runtime.failure().isPresent(),"unknown reaction stops runtime");h.assertValueEqual(charges(t),2,"payment stays committed");int operations=t.operations.size();t.runtime.prepare();t.runtime.prepare();h.assertValueEqual(charges(t),2,"no repeated payment");h.assertValueEqual(t.operations.size(),operations,"no repeated world work");
        }h.succeed();
    }
}
