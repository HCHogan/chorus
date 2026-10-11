package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeaponStatReloadTest {
    static final String D="chorus_d2:", FACTOR=D+"weapons_reload_duration";
    static CompiledEffects program(boolean incremental)throws Exception {
        var modules=new ArrayList<EffectProgram>();
        for(String name:List.of("weapon_stats","surplus","surplus_weapon","wellspring_targets","wellspring","character_stats","armor_stats","armor_stat_inputs","weapon_stat_reload")) {
            var data=json(name);
            if(incremental&&name.equals("surplus_weapon")) {
                var reload=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("reload");var repeat=reload.deepCopy();
                var insert=new JsonObject();insert.add("rounds",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"round\"}"));insert.add("repeat",repeat);reload.add("insert",insert);
            }
            modules.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        }return CompiledEffects.link(modules);
    }
    static EffectSource source(String holder,String id){return new EffectSource(id,D+"weapon_stat_reload",holder,new BuffInstance.Origin(holder,id,"",""),Set.of());}
    static class Harness extends SurplusTest.Harness {
        Harness(boolean charged,boolean incremental)throws Exception {super(WeaponStatReloadTest.program(incremental),false,charged?1:0,charged?1:0,charged?1:0);bind(source("player","weapons"));}
        void bind(EffectSource source){session.start(now(),SourceChange.bind(source));}
        void armor(String owner,double points){var before=state().equipment().getOrDefault(owner,Loadout.EMPTY);var slots=new TreeMap<>(before.slots());slots.put(D+"arms",ArmorStatInputsTest.gear(owner+"-armor","arms",points));session.start(now(),new EquipmentChange(owner,before,new Loadout(slots,before.drawn())).signal());}
        void armor(double points){armor("player",points);}
        void until(long time){session.observe(time,List.of());assertTrue(session.state().idle()&&session.state().engine().failure().isEmpty());}
        CalculationPipeline.Result timing(String weapon,double base,EffectState.Mode mode){return program.calculatePipeline(state().withMode(mode),"player",new EffectEvent("player",weapon,new BuffInstance.Origin("player",weapon,weapon,""),Set.of("chorus:manual_reload"),Map.of()),List.of(D+"weapon_reload",weapon.equals("b")?"test:shotgun_reload_time":"test:rifle_reload_time",D+"reload_animation"),new Measure(base,Unit.STAT_POINT));}
    }
    @Test void continuousCharacterStatUsesDurationAfterEachWeaponCurveAndStopsAtOneHundred()throws Exception {
        var h=new Harness(false,false);
        for(double points:List.of(0.,1.,9.,40.,99.,100.,101.,150.,200.)) {
            h.armor(points);
            for(var mode:EffectState.Mode.values())for(String weapon:List.of("a","b")) {
                var result=h.timing(weapon,10,mode);assertEquals((weapon.equals("a")?1.9:2.8)*(1-.001*Math.min(points,100)),result.output().value(),1e-12);
                assertEquals(10,result.steps().getFirst().output().value(),"character points do not add to weapon reload stat");
            }
        }
    }
    @Test void perkPointsClampBeforeTheCurveAndCharacterDurationMultipliesOtherAnimationFactors()throws Exception {
        var h=new Harness(true,false);h.armor(100);h.bind(new EffectSource("fast","test:fast_animation","player",new BuffInstance.Origin("player","fast","a",""),Set.of()));
        var result=h.timing("a",10,EffectState.Mode.PVE);assertEquals(70,result.steps().getFirst().output().value());assertEquals(1.3,result.steps().get(1).output().value(),1e-12);assertEquals(.8775,result.output().value(),1e-12);
        assertEquals(.975,result.withoutFactors(Set.of(FACTOR)).output().value(),1e-12);assertEquals(1.17,result.withoutFactors(Set.of(D+"reload_animation")).output().value(),1e-12);
        assertEquals(.675,result.withBase(new Measure(80,Unit.STAT_POINT)).output().value(),1e-12,"perk points clamp at 100 but both duration multipliers survive");
    }
    @Test void onlyTheReloadOwnersArmorAppliesAndDuplicateSystemSourcesDoNotMultiply()throws Exception {
        var h=new Harness(false,false);h.armor("giver",200);h.bind(source("giver","foreign"));assertEquals(1.9,h.timing("a",10,EffectState.Mode.PVE).output().value(),1e-12);
        h.armor(100);h.bind(source("player","duplicate"));var state=h.state();assertEquals(1.71,h.timing("a",10,EffectState.Mode.PVE).output().value(),1e-12);assertSame(state,h.state());
    }
    @Test void acceptedDurationSurvivesArmorChangesAndTheNextActualReloadUsesCurrentState()throws Exception {
        var h=new Harness(true,false);h.armor(100);h.spend("a");var first=h.reload();assertEquals(1.17,first.duration().value(),1e-12);
        h.armor(0);h.use("grenade");assertEquals(first,h.state().reloads().get("player"));h.until(first.dueAt()-1);assertEquals(4,h.state().ammunition().get("a").magazine());
        h.until(first.dueAt());assertEquals(5,h.state().ammunition().get("a").magazine());h.spend("a");var next=h.reload();assertEquals(1.65,next.duration().value(),1e-12);
        h.until(next.dueAt());assertEquals(28,h.state().ammunition().get("a").reserve().orElseThrow().rounds());
    }
    @Test void eachIncrementalInsertionReadsCurrentArmorAfterThePreviousAcceptedDeadline()throws Exception {
        var h=new Harness(true,true);h.armor(100);for(int i=0;i<3;i++)h.spend("a");var first=h.reload();h.armor(0);h.until(first.dueAt());
        assertEquals(3,h.state().ammunition().get("a").magazine());var second=h.state().reloads().get("player");assertEquals(1,second.step());assertEquals(1.3,second.duration().value(),1e-12);
        h.armor(100);h.until(second.dueAt());var third=h.state().reloads().get("player");assertEquals(2,third.step());assertEquals(1.17,third.duration().value(),1e-12);
        h.until(third.dueAt());assertEquals(5,h.state().ammunition().get("a").magazine());assertEquals(27,h.state().ammunition().get("a").reserve().orElseThrow().rounds());assertTrue(h.state().reloads().isEmpty());
    }
    @Test void stowingStillCancelsAndReturningCannotReviveTheOldScaledDeadline()throws Exception {
        var h=new Harness(true,false);h.armor(100);h.spend("a");var first=h.reload();var old=h.state().equipment().get("player");h.equip(new Loadout(old.slots(),Optional.of("test:secondary")));h.equip(old);
        h.until(first.dueAt());h.session.start(h.now(),new RuleEngine.Signal(WeaponReload.DUE,first));assertEquals(4,h.state().ammunition().get("a").magazine());assertTrue(h.state().reloads().isEmpty());
        var fresh=h.reload();assertEquals(1.17,fresh.duration().value(),1e-12);assertTrue(fresh.dueAt()>first.dueAt());
    }
    @Test void acceptedPlanRoundTripRetainsIndependentFactorsAfterSourceRemoval()throws Exception {
        var h=new Harness(true,false);h.armor(50);h.spend("a");var plan=h.reload();var restored=EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(plan),WeaponReload.Plan.class);
        assertEquals(plan,restored);h.session.start(h.now(),SourceChange.remove("weapons"));h.armor(200);
        assertEquals(1.235,restored.duration().value(),1e-12);assertEquals(1.3,restored.calculation().orElseThrow().withoutFactors(Set.of(FACTOR)).output().value(),1e-12);
        h.until(plan.dueAt());h.spend("a");assertEquals(1.3,h.reload().duration().value(),1e-12,"new query no longer has the unbound provider");
    }
}
