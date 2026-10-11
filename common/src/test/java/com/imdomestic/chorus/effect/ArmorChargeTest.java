package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.NumericQuery;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArmorChargeTest {
    static final String D="chorus_d2:";
    static EffectSource system(String owner){return new EffectSource("armor_charge/"+owner,D+"armor_charge_system",owner,new BuffInstance.Origin(owner,"armor_charge/"+owner,"",""),Set.of(),Map.of("receipt_seconds",new Measure(60,Unit.SECOND)));}
    static EffectSource mod(String owner,String name,int copy){String id=name+"/"+copy+"/"+owner;return new EffectSource(id,D+name,owner,new BuffInstance.Origin(owner,id,"",""),Set.of());}
    static class Harness extends StarvationTest.Harness {
        boolean unknownRecipient;
        Harness() throws Exception {super("armor_charge","armor_charge_mods","armor_charge_inputs");remove("starvation");equip(system("player"));bind("armor_inputs","test:armor_charge_inputs","player");}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){if(unknownRecipient&&r.command() instanceof EntityQuery)throw new IllegalStateException("unknown recipient");return super.world(r);}
        void mod(String name,int copy){equip(ArmorChargeTest.mod("player",name,copy));}
        void unmod(String name,int copy){remove(name+"/"+copy+"/player");}
        void action(String kind,String target,int stacks,int bonus){session.start(now(),new RuleEngine.Signal("test:"+kind+"_armor_charge",new EffectEvent("player",target,new BuffInstance.Origin("player","input","",""),Set.of(),Map.of("stacks",new Measure(stacks,Unit.COUNT),"bonus",new Measure(bonus,Unit.COUNT)))));healthy();}
        void gain(int stacks){action("gain","player",stacks,1);}
        void spend(int stacks){action("spend","player",stacks,0);}
        Optional<BuffInstance> buff(String name,String owner){return state().buffs().instances().values().stream().filter(b->b.key().holder().equals(owner)&&b.key().definition().equals(D+name)).findFirst();}
        Optional<BuffInstance> buff(String name){return buff(name,"player");}
        int charges(){return buff("armor_charge").map(BuffInstance::count).orElse(0);}
        long deadline(){return buff("armor_charge_decay").orElseThrow().deadline();}
        double stat(String name,double base){return p.attribute(state(),"player",D+name+"_stat",new Measure(base,Unit.STAT_POINT),new NumericQuery.Path(Set.of(),Set.of())).output().value();}
        void orb(){spawn("orb_of_power","giver");finish(spawns.size()-1,true);}
    }
    @Test void actualPickupNeedsEquippedChargeModAndFullSuperPermissionTracksRemainingChargeCapacity()throws Exception{
        var h=new Harness();h.fill("super");h.spawn("orb_of_power","giver");assertFalse(h.eligible(0));h.gain(1);assertEquals(0,h.charges());h.mod("grenade_font",0);assertTrue(h.eligible(0));
        h.finish(0,true);assertEquals(1,h.charges());assertEquals(70,h.stat("grenade",50));assertEquals(1,h.account("super"));h.orb();h.orb();assertEquals(3,h.charges());h.spawn("orb_of_power","giver");assertFalse(h.eligible(3));h.spend(1);assertTrue(h.eligible(3));
    }
    @Test void chargedUpCopiesSetSharedFourFiveSixCapacityAndRemovingCopiesClampsExistingStacks()throws Exception{
        var h=new Harness();for(int n=1;n<=3;n++){h.mod("charged_up",n);h.gain(10);assertEquals(3+n,h.charges());}h.unmod("charged_up",3);assertEquals(5,h.charges());h.unmod("charged_up",2);assertEquals(4,h.charges());h.unmod("charged_up",1);assertEquals(0,h.charges());assertTrue(h.buff("armor_charge_pickup_permission").isEmpty());
    }
    @Test void stacksOnStacksAddsOneAcrossDuplicateCopiesAndExplicitExemptSourcesSkipTheBonus()throws Exception{
        var h=new Harness();for(int n=0;n<3;n++)h.mod("charged_up",n);h.mod("stacks_on_stacks",0);h.mod("stacks_on_stacks",1);h.gain(1);assertEquals(2,h.charges());h.action("gain","player",1,0);assertEquals(3,h.charges());h.gain(1);assertEquals(5,h.charges());h.gain(1);assertEquals(6,h.charges());
    }
    @Test void allSixFontsUseCurrentCopyTotalsAndSharedChargeWithoutStackingTheBenefitPerSource()throws Exception{
        var h=new Harness();for(String stat:List.of("health","class","grenade","melee","super","weapons")){h.mod(stat+"_font",0);assertEquals(50,h.stat(stat,50));}h.gain(1);
        for(String stat:List.of("health","class","grenade","melee","super","weapons")){assertEquals(70,h.stat(stat,50));h.mod(stat+"_font",1);assertEquals(90,h.stat(stat,50));h.mod(stat+"_font",2);assertEquals(100,h.stat(stat,50));assertEquals(200,h.stat(stat,195));h.unmod(stat+"_font",1);assertEquals(90,h.stat(stat,50));}
        h.spend(1);for(String stat:List.of("health","class","grenade","melee","super","weapons"))assertEquals(50,h.stat(stat,50));
    }
    @Test void newGainsDoNotRefreshTheRunningTenSecondDecayAndEachExpiryConsumesExactlyOne()throws Exception{
        var h=new Harness();h.mod("grenade_font",0);h.gain(1);assertEquals(10_000_000,h.deadline());h.until(9_000_000);h.gain(2);assertEquals(10_000_000,h.deadline());h.until(10_000_000);assertEquals(2,h.charges());assertEquals(20_000_000,h.deadline());h.until(30_000_000);assertEquals(0,h.charges());assertTrue(h.buff("armor_charge_decay").isEmpty());assertEquals(50,h.stat("grenade",50));
    }
    @Test void timeDilationHasFifteenEighteenTwentySecondsOnlyWhenAPassiveModIsActive()throws Exception{
        for(int copies=1;copies<=3;copies++){var h=new Harness();for(int n=0;n<copies;n++)h.mod("time_dilation",n);h.gain(3);h.until(80_000_000);assertEquals(3,h.charges());assertTrue(h.buff("armor_charge_decay").isEmpty());h.mod("health_font",0);long period=new long[]{15,18,20}[copies-1]*1_000_000;assertEquals(80_000_000+period,h.deadline());h.until(80_000_000+period);assertEquals(2,h.charges());}
    }
    @Test void SuccessfulSpendResetsTheWholeDecayIntervalButInsufficientSpendDoesNot()throws Exception{
        var h=new Harness();h.mod("class_font",0);h.gain(3);h.until(8_000_000);h.spend(4);assertEquals(3,h.charges());assertEquals(10_000_000,h.deadline());h.spend(1);assertEquals(2,h.charges());assertEquals(18_000_000,h.deadline());h.until(10_000_000);assertEquals(2,h.charges());h.until(18_000_000);assertEquals(1,h.charges());
    }
    @Test void RemovingLastPassiveStopsDecayAndReaddingStartsANewIntervalWithoutLosingNonPassiveCharges()throws Exception{
        var h=new Harness();h.mod("charged_up",0);h.mod("health_font",0);h.gain(3);h.until(9_000_000);h.unmod("health_font",0);assertTrue(h.buff("armor_charge_decay").isEmpty());h.until(30_000_000);assertEquals(3,h.charges());h.mod("health_font",0);assertEquals(40_000_000,h.deadline());
    }
    @Test void unrelatedSourceMutationsDoNotResetTimerAndPeriodChangesUseNewConfiguredInterval()throws Exception{
        var h=new Harness();h.mod("health_font",0);h.gain(2);h.until(5_000_000);h.mod("health_font",0);h.equip(StarvationTest.fragment("unrelated",10));assertEquals(10_000_000,h.deadline());h.mod("time_dilation",0);assertEquals(20_000_000,h.deadline());h.unmod("time_dilation",0);assertEquals(15_000_000,h.deadline());
    }
    @Test void pickupReceiptsSurviveSpendRebindingAndCheckpointWhileNewActualPickupsRemainValid()throws Exception{
        var h=new Harness();h.mod("charged_up",0);h.orb();var old=h.fact(0);h.spend(1);h.remove("armor_charge/player");h.equip(system("player"));h.restore();h.replay(List.of(old));assertEquals(0,h.charges());h.orb();assertEquals(1,h.charges());
    }
    @Test void foreignGiverUsesRecipientsCurrentConfigurationAndNeverCreditsTheGiver()throws Exception{
        var h=new Harness();h.equip(system("giver"));h.equip(mod("giver","charged_up",0));h.equip(mod("giver","stacks_on_stacks",0));h.action("gain","giver",1,1);assertEquals(0,h.charges());assertEquals(2,h.buff("armor_charge","giver").orElseThrow().count());assertEquals("player",h.buff("armor_charge","giver").orElseThrow().origin().owner());
    }
    @Test void actualDeathClearsChargesAndTheirPassiveBenefitWithoutRemovingEquippedSources()throws Exception{
        var h=new Harness();h.mod("health_font",0);h.gain(3);h.damage("giver","player",2000);assertEquals(0,h.charges());assertTrue(h.buff("armor_charge_decay").isEmpty());assertEquals(50,h.stat("health",50));assertTrue(h.state().sources().containsKey("health_font/0/player"));h.gain(1);assertEquals(0,h.charges());
    }
    @Test void missingOrUnknownRecipientDoesNotGrantStacksOrPretendThatAnObservationSucceeded()throws Exception{
        var h=new Harness();h.mod("health_font",0);h.kinds.remove("player");h.gain(1);assertEquals(0,h.charges());var other=new Harness();other.mod("health_font",0);other.unknownRecipient=true;assertThrows(IllegalStateException.class,()->other.gain(1));assertEquals(0,other.charges());assertThrows(IllegalStateException.class,()->other.until(10_000_000));
    }
}
