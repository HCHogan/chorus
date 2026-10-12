package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.WeaponCharge;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Reference full-draw curves and actual charged hits; minimum release, damage and flight remain synthetic. */
class BowTimingPerksTest {
    static final String D="chorus_d2:", TEMPO="archers_tempo", WARM="successful_warm_up";
    static final List<String> PARTS=List.of("explosive_head","explosive_head_test_calibration","explosive_payload","explosive_payload_test_calibration","weapon_stats","weapon_charge","weapon_charge_time","bow_draw_curves","archers_tempo","successful_warm_up");
    static CompiledEffects program(JsonObject data)throws Exception{
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        for(String n:PARTS)parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(n)).getOrThrow());return CompiledEffects.link(parts);
    }
    static Loadout.Gear gear(String id,String variant,boolean perks){
        var sockets=new HashMap<String,String>();sockets.put("perk","normal");if(perks){sockets.put(TEMPO,variant);sockets.put(WARM,variant);}return new Loadout.Gear(id,"test:bow",sockets);
    }
    static class Harness extends WeaponChargeTest.Harness {
        Harness()throws Exception{this(json("bow_charge_weapon"));}
        Harness(JsonObject data)throws Exception{super(BowTimingPerksTest.program(data));configure("normal","primary",true);}
        void configure(String variant,String drawn,boolean perks){equip(gear("a",variant,perks),gear("b","enhanced",true),drawn);}
        void fact(String name,String owner,String weapon,Set<String> tags,boolean applied){
            int id=++sequence;var refs=name.equals("kill")?Map.of("death_id","death-"+id):Map.<String,String>of();
            var e=new EffectEvent(owner,"target-"+id,new BuffInstance.Origin(owner,"shot",weapon,""),tags,Map.of(),Map.of("applied",applied),refs);
            session.start(now(),new RuleEngine.Signal("chorus:"+name,e));healthy();
        }
        void precision(){fact("hit","player","a",Set.of("chorus:weapon_damage","chorus:precision"),true);}
        void killFact(){fact("kill","player","a",Set.of("chorus:weapon_kill"),true);}
        Optional<BuffInstance> buff(String name,String weapon){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(D+name)&&b.origin().weapon().equals(weapon)).findFirst();}
        long remaining(String name){return buff(name,"a").orElseThrow().deadline()-now();}
        double time(String profile,String weapon,double seconds){
            var e=new EffectEvent("player",weapon,new BuffInstance.Origin("player","query",weapon,""),Set.of(),Map.of());
            return program.calculate(state(),"player",e,D+profile,new Measure(seconds,Unit.SECOND),List.of()).output().value();
        }
        void shot(boolean precision,boolean kill){
            String token="shot-"+ ++sequence;assertEquals(WeaponCharge.Outcome.ACCEPTED,input(token,WeaponCharge.Edge.PRESS).outcome());until(charge().fullAt());
            assertEquals(WeaponCharge.Outcome.FIRED,input(token,WeaponCharge.Edge.RELEASE).outcome());entity("target",kill?1:1000,-5);hit(5,precision);
        }
    }
    @Test void bothReferenceFramesClampStatsAndProduceSecondsBeforePerkMultiplication()throws Exception{
        var h=new Harness();
        for(String frame:List.of("lightweight","precision"))for(double stat:new double[]{-20,0,50,100,120}){
            double slope=frame.equals("lightweight")?4:3.6;
            assertEquals((900-slope*Math.clamp(stat,0,100))/1100,h.query(D+frame+"_bow_draw_time","a",stat),1e-12);
        }
        h.input("reference",WeaponCharge.Edge.PRESS);assertEquals(636_364,h.charge().fullAt());assertEquals(100_000,h.charge().minimum().micros());
        assertTrue(h.charge().full().calculation().isPresent());
    }
    @Test void ordinaryAndEnhancedTempoRefreshAtPrecisionHitsAndExpireAtTheirExactBoundaries()throws Exception{
        for(String variant:List.of("normal","enhanced")){
            var h=new Harness();h.configure(variant,"primary",true);h.precision();long duration=variant.equals("normal")?3_000_000:4_000_000;
            assertEquals(duration,h.remaining(TEMPO));assertEquals(.75,h.time("weapon_draw_time","a",1));
            h.until(1_000_000);h.precision();assertEquals(duration,h.remaining(TEMPO));h.until(1_000_000+duration-1);assertEquals(.75,h.time("weapon_draw_time","a",1));
            h.until(1_000_000+duration);assertTrue(h.buff(TEMPO,"a").isEmpty());assertEquals(1,h.time("weapon_draw_time","a",1));
        }
    }
    @Test void warmupStartsAtSixSecondsThenExtendsRemainingTimeWithAnExplicitTwentySecondCap()throws Exception{
        for(String variant:List.of("normal","enhanced")){
            var h=new Harness();h.configure(variant,"primary",true);h.killFact();assertEquals(6_000_000,h.remaining(WARM));
            h.until(2_000_000);h.killFact();assertEquals(variant.equals("normal")?8_000_000:9_000_000,h.remaining(WARM));
            for(int i=0;i<10;i++)h.killFact();assertEquals(20_000_000,h.remaining(WARM));
            h.until(21_999_999);assertEquals(.625,h.time("weapon_draw_time","a",1));h.until(22_000_000);assertTrue(h.buff(WARM,"a").isEmpty());
            h.killFact();assertEquals(6_000_000,h.remaining(WARM));
        }
    }
    @Test void physicalPrecisionKillActivatesBothAndNextDrawUsesTheProduct()throws Exception{
        var h=new Harness();h.shot(true,true);assertEquals(0,h.health.get("target"));assertEquals(3_000_000,h.remaining(TEMPO));assertEquals(6_000_000,h.remaining(WARM));
        h.until(h.now()+200_000);h.input("faster",WeaponCharge.Edge.PRESS);var plan=h.charge();assertEquals(298_296,plan.fullAt()-plan.startedAt());assertEquals(100_000,plan.minimum().micros());
        assertEquals(.46875,h.time("weapon_draw_time","a",1));assertEquals(1,h.time("weapon_draw_time","b",1));
        h.until(plan.fullAt());h.input("faster",WeaponCharge.Edge.RELEASE);assertEquals(2,h.shots.size());assertEquals(8,h.ammo());
    }
    @Test void tempoDoesNotChangeFusionChargeButWarmupUsesTheSameTimerForBothProfiles()throws Exception{
        var h=new Harness();h.precision();assertEquals(1,h.time("weapon_charge_time","a",1));h.killFact();assertEquals(.625,h.time("weapon_charge_time","a",1));assertEquals(.46875,h.time("weapon_draw_time","a",1));
        h.until(3_000_000);assertEquals(.625,h.time("weapon_draw_time","a",1));h.until(6_000_000);assertEquals(1,h.time("weapon_charge_time","a",1));
    }
    @Test void bodyHitsNonWeaponPrecisionImmuneHitsAndOtherOwnersOrWeaponsCannotGrantTempo()throws Exception{
        var h=new Harness();
        h.fact("hit","player","a",Set.of("chorus:weapon_damage"),true);
        h.fact("hit","player","a",Set.of("chorus:precision"),true);
        h.fact("hit","player","a",Set.of("chorus:weapon_damage","chorus:precision"),false);
        h.fact("hit","player","a",Set.of("chorus:weapon_damage","chorus:precision","chorus:explosive_perk_damage"),true);
        h.fact("hit","other","a",Set.of("chorus:weapon_damage","chorus:precision"),true);
        h.fact("hit","player","missing",Set.of("chorus:weapon_damage","chorus:precision"),true);
        h.fact("kill","player","a",Set.of(),true);h.fact("kill","other","a",Set.of("chorus:weapon_kill"),true);
        assertTrue(h.buff(TEMPO,"a").isEmpty()&&h.buff(WARM,"a").isEmpty());
    }
    @Test void acceptedDrawDeadlineSurvivesExpiryAndDomainCheckpoint()throws Exception{
        var h=new Harness();h.precision();h.killFact();h.until(2_950_000);h.input("held",WeaponCharge.Edge.PRESS);var plan=h.charge();h.restore();
        h.until(3_000_000);assertTrue(h.buff(TEMPO,"a").isEmpty());assertEquals(plan.fullAt(),h.charge().fullAt());h.until(plan.fullAt());h.input("held",WeaponCharge.Edge.RELEASE);assertEquals(9,h.ammo());
        h.until(h.now()+200_000);h.input("later",WeaponCharge.Edge.PRESS);assertEquals(397_728,h.charge().fullAt()-h.charge().startedAt());
    }
    @Test void stowClearsBothAndLatePhysicalPrecisionKillCannotRegrantThem()throws Exception{
        var h=new Harness();h.precision();h.killFact();h.input("flight",WeaponCharge.Edge.PRESS);h.until(h.charge().fullAt());h.input("flight",WeaponCharge.Edge.RELEASE);
        h.configure("normal","secondary",true);assertTrue(h.buff(TEMPO,"a").isEmpty()&&h.buff(WARM,"a").isEmpty());h.entity("target",1,-5);h.hit(5,true);assertEquals(0,h.health.get("target"));assertTrue(h.buff(TEMPO,"a").isEmpty()&&h.buff(WARM,"a").isEmpty());
        h.fact("hit","player","b",Set.of("chorus:weapon_damage","chorus:precision"),true);assertEquals(4_000_000,h.buff(TEMPO,"b").orElseThrow().deadline()-h.now());h.configure("normal","primary",true);assertEquals(1,h.time("weapon_draw_time","a",1));
    }
    @Test void removingPhysicalPerkSocketsClearsTheirBuffsAndReinstallDoesNotRestoreThem()throws Exception{
        var h=new Harness();h.precision();h.killFact();h.configure("normal","primary",false);assertTrue(h.buff(TEMPO,"a").isEmpty()&&h.buff(WARM,"a").isEmpty());h.configure("normal","primary",true);assertEquals(1,h.time("weapon_draw_time","a",1));
    }
    @Test void delayedExplosiveHeadKillHasWeaponCreditWithoutPrecisionRefresh()throws Exception{
        var h=new Harness();h.input("delayed",WeaponCharge.Edge.PRESS);h.until(h.charge().fullAt());h.input("delayed",WeaponCharge.Edge.RELEASE);h.entity("target",10,-5);h.hit(5,false);
        assertEquals(5,h.health.get("target"));assertTrue(h.buff(WARM,"a").isEmpty());h.until(h.now()+200_000);assertEquals(0,h.health.get("target"));assertEquals(6_000_000,h.remaining(WARM));assertTrue(h.buff(TEMPO,"a").isEmpty());
    }
}
