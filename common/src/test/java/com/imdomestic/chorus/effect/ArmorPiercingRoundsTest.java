package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArmorPiercingRoundsTest {
    static final List<String> PARTS=List.of("armor_piercing_rounds","weapon_ballistics","weapon_stats","weapon_range_curves","weapon_range_test_calibration","armor_piercing_test_targets");
    static CompiledEffects program(JsonObject weapon)throws Exception {
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,weapon).getOrThrow());
        for(String name:PARTS)parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(name)).getOrThrow());return CompiledEffects.link(parts);
    }
    static Loadout.Gear gun(String id,String variant){return new Loadout.Gear(id,"test:rifle",Map.of("magazine",variant));}
    static class Harness extends ChainReactionTest.Harness {
        final List<Hitscan.PiercingQuery> rays=new ArrayList<>(); final List<DamageReceipt> receipts=new ArrayList<>();
        Harness()throws Exception{this(json("armor_piercing_weapon"));}
        Harness(JsonObject data)throws Exception{super(program(data),"test:unused",false,Map.of(),Map.of());}
        @Override void equip(String normal,String enhanced,String draw){
            var next=new Loadout(Map.of("test:primary",gun("a",normal),"test:secondary",gun("b",enhanced)),Optional.of("test:"+draw));
            session.start(now(),new EquipmentChange("player",state().equipment().getOrDefault("player",Loadout.EMPTY),next).signal());healthy();
        }
        void target(String id,double distance){entity(id,1000,positions.get("player").x()+distance);}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request){return switch(request.command()){
            case Hitscan.PiercingQuery q -> {
                rays.add(q);var ray=q.ray();var start=ray.position().orElseThrow();
                var targets=positions.entrySet().stream().filter(e->!e.getKey().equals("player")&&health.get(e.getKey())>0&&e.getValue().x()>=start.x()&&e.getValue().x()<=start.x()+ray.range()).sorted(Comparator.comparingDouble(e->e.getValue().x())).limit(1L+q.pierces()).toList();
                var path=new ArrayList<Hitscan.PathContact>();
                for(var e:targets){var p=e.getValue();var geometry=new ProjectileFlight.Geometry(new WorldPosition(p.dimension(),p.x()-.3,p.y()-.5,p.z()-.3),.6,2,.6,0);
                    var impact=new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,p,Optional.of(e.getKey()),0,0,0,0).withGeometry(geometry);if(ray.member().isPresent())impact=impact.withMember(ray.member());
                    path.add(new Hitscan.PathContact(new Hitscan.Result(ray,Hitscan.Outcome.ENTITY,Optional.of(impact)),path.size()+1,path.size()+1==1L+q.pierces()));
                }
                if(targets.size()<1L+q.pierces()){
                    var p=new WorldPosition(start.dimension(),start.x()+ray.range(),start.y(),start.z());var impact=new ProjectileFlight.Impact(ProjectileFlight.End.EXPIRED,p,Optional.empty(),0,0,0,0);if(ray.member().isPresent())impact=impact.withMember(ray.member());
                    path.add(new Hitscan.PathContact(new Hitscan.Result(ray,Hitscan.Outcome.RANGE,Optional.of(impact)),path.size()+1,true));
                }
                yield new Hitscan.PathResult(q,path);
            }
            case DamageCommand d -> {
                hits.add(d);var outgoing=program.outgoing(state(),d,d.amount());double amount=outgoing.orElseThrow().output().value();amounts.add(amount);
                var plan=program.shields(state(),d,amount);double loss=Math.min(health.get(d.target()),plan.budget().toVanilla());health.compute(d.target(),(_,hp)->hp-loss);
                var receipt=new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.APPLIED,plan.budget().shieldLoss(),0,loss,Optional.empty(),false,Optional.empty(),plan.layers());receipts.add(receipt);
                yield new RuleEngine.WorldReceipt(receipt,List.of(),plan.commit());
            }
            default -> super.world(request);
        };}
        double query(String profile,String weapon,double base,Unit unit){return program.calculate(state(),"player",new EffectEvent("player",weapon,new BuffInstance.Origin("player","query",weapon,""),Set.of(),Map.of()),profile,new Measure(base,unit),List.of()).output().value();}
        void shield(String target,String name){bind("shield-input","test:ap_shields","");session.start(now(),new RuleEngine.Signal("test:ap_"+name,new EffectEvent("player",target,new BuffInstance.Origin("player","shield-input","",""),Set.of(),Map.of())));healthy();}
    }
    @Test void ordinaryAndEnhancedRangeEnterTheirOwnClampedCurveAndPiercingGrant()throws Exception{
        var h=new Harness();assertEquals(55,h.query("chorus_d2:weapon_range","a",50,Unit.STAT_POINT));assertEquals(57,h.query("chorus_d2:weapon_range","b",50,Unit.STAT_POINT));
        assertEquals(50,h.query("chorus_d2:weapon_range","other",50,Unit.STAT_POINT));assertEquals(100,h.query("chorus_d2:weapon_range","b",99,Unit.STAT_POINT));assertEquals(1,h.query("chorus_d2:weapon_piercing_grant","a",0,Unit.COUNT));
        h.equip("none","enhanced","primary");assertEquals(50,h.query("chorus_d2:weapon_range","a",50,Unit.STAT_POINT));assertEquals(0,h.query("chorus_d2:weapon_piercing_grant","a",0,Unit.COUNT));assertEquals(57,h.query("chorus_d2:weapon_range","b",50,Unit.STAT_POINT));
    }
    @Test void onePaidRoundPiercesTwoTargetsWithIndependentDistancesAndNoThirdDamage()throws Exception{
        var h=new Harness();h.target("first",20);h.target("second",30);h.target("third",40);h.fire();
        assertEquals(1,h.rays.getFirst().pierces());assertEquals(2,h.hits.size());assertEquals(9,h.state().ammunition().get("a").magazine());assertEquals(30,h.state().ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(WeaponRangeFalloffTest.loss(20,17.625,40,.5),h.amounts.getFirst(),1e-10);assertEquals(WeaponRangeFalloffTest.loss(30,17.625,40,.5),h.amounts.getLast(),1e-10);assertEquals(1000,h.health.get("third"));assertTrue(h.state().shotGroups().isEmpty());
    }
    @Test void noPerkAndEnhancedEquipmentActuallyChangeTheNextShot()throws Exception{
        var h=new Harness();h.target("first",20);h.target("second",30);h.equip("none","enhanced","primary");h.fire();assertEquals(1,h.hits.size());assertEquals(0,h.rays.getLast().pierces());assertEquals(17,h.hits.getLast().impact().number("range_start",Unit.METER).value());
        h.until(200_000);h.equip("none","enhanced","secondary");h.fire();assertEquals(3,h.hits.size());assertEquals(17.875,h.hits.getLast().impact().number("range_start",Unit.METER).value());assertEquals(9,h.state().ammunition().get("b").magazine());
    }
    @Test void onlyElementalAndBarrierLayersReceiveFivePercentInBothModesAndVersions()throws Exception{
        for(boolean enhanced:List.of(false,true))for(var mode:EffectState.Mode.values())for(var name:List.of("elemental","barrier","guardian","overshield","plain","immune_barrier")){
            var h=new Harness();h.equip(enhanced?"enhanced":"normal","none","primary");h.target("target",5);h.shield("target",name);
            var s=h.state().withMode(mode);var d=new DamageCommand("target",new BuffInstance.Origin("player","test","a",""),10,"minecraft:generic",Set.of("chorus:weapon_direct"),Set.of(),false,Optional.empty(),Optional.empty(),ImpactData.EMPTY,Optional.of("chorus_d2:weapon_shield_damage"));
            var plan=h.program.shields(s,d,10);boolean bonus=name.equals("elemental")||name.equals("barrier")||name.equals("immune_barrier");assertEquals(bonus?1.05:1,plan.layers().getFirst().attackScaling().orElseThrow().output().value(),1e-10);
            assertEquals(name.equals("immune_barrier")?0:10-5.25/(bonus?1.05:1),plan.budget().toVanilla(),1e-10);
        }
    }
    @Test void shieldOverflowNeverBoostsHealthOrTheNextLayerAndSourceScopeRejectsOtherDamage()throws Exception{
        var h=new Harness();h.target("target",5);h.shield("target","elemental");h.fire();assertEquals(10,h.amounts.getLast());assertEquals(5.25,h.receipts.getLast().shieldLoss());assertEquals(5,h.receipts.getLast().healthLoss());
        var captured=h.hits.getLast();var snapshot=captured.snapshot().orElseThrow();
        h.equip("none","none","primary");var s=h.state();s=s.withBuffs(Buffs.grant(s.buffs(),h.program.buff("test:ap_guardian"),"target","target",captured.source(),1,1,BuffDefinition.FOREVER).store());
        assertEquals(4.75,h.program.shields(s,snapshot.command("target",captured.impact()),10).budget().toVanilla(),1e-10);
    }
    @Test void anotherWeaponOwnerOrExplosiveProcCannotBorrowTheShieldBonus()throws Exception{
        var h=new Harness();h.target("target",5);h.shield("target","elemental");
        for(String owner:List.of("player","other-player"))for(String weapon:List.of("a","other"))for(Set<String> tags:List.of(Set.<String>of(),Set.of("chorus:weapon_direct"),Set.of("chorus:weapon_direct","chorus:explosive_perk"),Set.of("chorus:weapon_direct","chorus:explosive_perk_damage"))){
            var d=new DamageCommand("target",new BuffInstance.Origin(owner,"x",weapon,""),10,"minecraft:generic",tags,Set.of(),false,Optional.empty(),Optional.empty(),ImpactData.EMPTY,Optional.of("chorus_d2:weapon_shield_damage"));
            boolean applies=owner.equals("player")&&weapon.equals("a")&&tags.equals(Set.of("chorus:weapon_direct"));assertEquals(applies?1.05:1,h.program.shields(h.state(),d,10).layers().getFirst().trace().multiplier(),1e-10);
        }
    }
    @Test void capturedShieldPerkSurvivesSourceRemovalButQualifiesEachLateLayerSeparately()throws Exception{
        var h=new Harness();h.target("target",5);h.fire();var d=h.hits.getFirst();h.equip("none","none","primary");
        var state=h.state();for(String shield:List.of("elemental","guardian"))state=state.withBuffs(Buffs.grant(state.buffs(),h.program.buff("test:ap_"+shield),"late","late",d.source(),1,1,BuffDefinition.FOREVER).store());
        var plan=h.program.shields(state,d.snapshot().orElseThrow().command("late",d.impact()),20);
        assertEquals(1.05,plan.layers().getFirst().trace().multiplier(),1e-10);assertEquals(1,plan.layers().getLast().trace().multiplier());assertEquals(9.75,plan.budget().toVanilla(),1e-10);
    }

    @Test void intrinsicPenetrationIsNotReducedAndFiniteEmptyPathClosesTheShot()throws Exception{
        var data=json("armor_piercing_weapon");var steps=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire");steps.get(steps.size()-1).getAsJsonObject().getAsJsonObject("pierces").getAsJsonArray("of").get(0).getAsJsonObject().addProperty("value",3);
        var h=new Harness(data);h.fire();assertEquals(3,h.rays.getFirst().pierces());assertTrue(h.hits.isEmpty());assertTrue(h.state().shotGroups().isEmpty());assertEquals(9,h.state().ammunition().get("a").magazine());
    }
    @Test void definitionsAndEquippedStateRoundTripWithoutLosingThePerk()throws Exception{
        var h=new Harness();var encoded=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.program.program()).getOrThrow();assertEquals(h.program.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,encoded).getOrThrow());
        h.restore();h.target("target",5);h.shield("target","elemental");h.fire();assertEquals(5,h.receipts.getLast().healthLoss());assertEquals(1,h.rays.getLast().pierces());
    }
}
