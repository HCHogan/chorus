package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RicochetRoundsTest {
    static CompiledEffects program(JsonObject weapon)throws Exception {
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,weapon).getOrThrow());
        for(String name:List.of("ricochet_rounds","armor_piercing_rounds","weapon_ballistics","weapon_stats","weapon_range_curves","weapon_range_test_calibration"))
            parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(name)).getOrThrow());return CompiledEffects.link(parts);
    }
    static Loadout.Gear gun(String id,String variant,int bounces,boolean piercing){
        var choices=new HashMap<String,String>();choices.put("magazine",variant);if(piercing)choices.put("penetration","on");
        return new Loadout.Gear(id,"test:rifle",choices,Map.of("ricochet_bounces",new Measure(bounces,Unit.COUNT)));
    }
    static class Harness extends ChainReactionTest.Harness {
        final List<RicochetHitscan.Query> rays=new ArrayList<>();boolean piercing;int bounces=1;
        Harness()throws Exception{this(json("ricochet_rounds_weapon"));}
        Harness(JsonObject data)throws Exception{super(program(data),"test:unused",false,Map.of(),Map.of());}
        @Override void equip(String a,String b,String draw){
            // The superclass performs initial equipment installation before subclass field initialization.
            int count=bounces==0?1:bounces;
            var next=new Loadout(Map.of("test:primary",gun("a",a,count,piercing),"test:secondary",gun("b",b,count,piercing)),Optional.of("test:"+draw));
            session.start(now(),new EquipmentChange("player",state().equipment().getOrDefault("player",Loadout.EMPTY),next).signal());healthy();
        }
        double query(String profile,String weapon,double base,Unit unit){return program.calculate(state(),"player",new EffectEvent("player",weapon,new BuffInstance.Origin("player","query",weapon,""),Set.of(),Map.of()),profile,new Measure(base,unit),List.of()).output().value();}
        ProjectileFlight.Impact contact(Hitscan.Query q,ProjectileFlight.End end,double x,String target){
            var p=new WorldPosition("test:world",x,0,0);var impact=new ProjectileFlight.Impact(end,p,Optional.ofNullable(target),end==ProjectileFlight.End.BLOCK?-1:0,0,0,0).withMember(q.member());
            return target==null?impact:impact.withGeometry(new ProjectileFlight.Geometry(new WorldPosition("test:world",x-.3,-1,-.3),.6,2,.6,0));
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){
            if(r.command() instanceof RicochetHitscan.Query q){
                rays.add(q);var ray=q.ray();var wall=contact(ray,ProjectileFlight.End.BLOCK,10,null);var path=new ArrayList<RicochetHitscan.Contact>();
                path.add(new RicochetHitscan.Contact(ray,Hitscan.Outcome.BLOCK,Optional.of(wall),1,q.bounces()>0?1:0,0,q.bounces()==0,0,false));
                if(q.bounces()>0){var reflected=RicochetHitscan.reflected(ray,wall);int count=Math.min(2,q.pierces()+1);
                    for(int i=0;i<count;i++){String id="target"+i;entity(id,1000,5-i*5);path.add(new RicochetHitscan.Contact(reflected,Hitscan.Outcome.ENTITY,Optional.of(contact(reflected,ProjectileFlight.End.ENTITY,5-i*5,id)),2+i,1,i+1,i==q.pierces(),20,false));}
                    if(count<=q.pierces())path.add(new RicochetHitscan.Contact(reflected,Hitscan.Outcome.RANGE,Optional.of(contact(reflected,ProjectileFlight.End.EXPIRED,10-reflected.range(),null)),count+2,1,count,true,20,false));
                }
                return new RicochetHitscan.Result(q,path);
            }return super.world(r);
        }
    }
    @Test void ordinaryAndEnhancedStatsStayOnTheirOwnWeaponAndClampAfterAddition()throws Exception {
        var h=new Harness();assertEquals(55,h.query("chorus_d2:weapon_range","a",50,Unit.STAT_POINT));assertEquals(56,h.query("chorus_d2:weapon_range","b",50,Unit.STAT_POINT));
        assertEquals(60,h.query("chorus_d2:weapon_stability","a",50,Unit.STAT_POINT));assertEquals(61,h.query("chorus_d2:weapon_stability","b",50,Unit.STAT_POINT));
        assertEquals(100,h.query("chorus_d2:weapon_stability","b",99,Unit.STAT_POINT));assertEquals(100,h.query("chorus_d2:weapon_range","a",99,Unit.STAT_POINT));assertEquals(50,h.query("chorus_d2:weapon_range","other",50,Unit.STAT_POINT));
        h.equip("none","enhanced","primary");assertEquals(0,h.query("chorus_d2:weapon_ricochet_grant","a",0,Unit.COUNT));assertEquals(50,h.query("chorus_d2:weapon_stability","a",50,Unit.STAT_POINT));assertEquals(1,h.query("chorus_d2:weapon_ricochet_grant","b",0,Unit.COUNT));
    }
    @Test void onePaidBulletReflectsAndUsesTravelledDistanceWithEachVersionsRange()throws Exception {
        for(String variant:List.of("normal","enhanced")){var h=new Harness();h.equip(variant,"none","primary");h.fire();assertEquals(1,h.rays.size());assertEquals(1,h.rays.getFirst().bounces());assertEquals(0,h.rays.getFirst().pierces());assertEquals(1,h.hits.size());
            double start=variant.equals("normal")?17.625:17.75;assertEquals(25,h.hits.getFirst().impact().number("distance",Unit.METER).value());assertEquals(start,h.hits.getFirst().impact().number("range_start",Unit.METER).value());
            assertEquals(WeaponRangeFalloffTest.loss(25,start,40,.5),h.amounts.getFirst(),1e-10);assertEquals(9,h.state().ammunition().get("a").magazine());assertTrue(h.state().shotGroups().isEmpty());}
    }
    @Test void unequippedWeaponStopsAtWallAndSwitchingToEnhancedEnablesTheNextShot()throws Exception {
        var h=new Harness();h.equip("none","enhanced","primary");h.fire();assertEquals(0,h.rays.getFirst().bounces());assertTrue(h.hits.isEmpty());
        h.until(200_000);h.equip("none","enhanced","secondary");h.fire();assertEquals(1,h.rays.getLast().bounces());assertEquals(1,h.hits.size());assertEquals("b",h.hits.getFirst().source().weapon());assertEquals(9,h.state().ammunition().get("a").magazine());assertEquals(9,h.state().ammunition().get("b").magazine());
    }
    @Test void configuredBounceGrantDoesNotReduceIntrinsicBouncesAndDoesNotGrantPenetration()throws Exception {
        var d=json("ricochet_rounds_weapon");var steps=d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire");steps.get(steps.size()-1).getAsJsonObject().getAsJsonObject("bounces").getAsJsonArray("of").get(0).getAsJsonObject().addProperty("value",4);
        var h=new Harness(d);h.bounces=2;h.equip("normal","none","primary");assertEquals(2,h.query("chorus_d2:weapon_ricochet_grant","a",0,Unit.COUNT));h.fire();assertEquals(4,h.rays.getFirst().bounces());assertEquals(0,h.rays.getFirst().pierces());assertFalse(h.rays.getFirst().hitOriginAfterBounce());assertEquals(1,h.rays.getFirst().hitsPerTarget());
    }
    @Test void penetrationCompositionKeepsOnePaymentAndIndependentTravelDistances()throws Exception {
        var h=new Harness();h.piercing=true;h.equip("normal","none","primary");h.fire();assertEquals(1,h.rays.getFirst().pierces());assertEquals(2,h.hits.size());assertEquals(60,h.query("chorus_d2:weapon_range","a",50,Unit.STAT_POINT));
        assertEquals(WeaponRangeFalloffTest.loss(25,18.25,40,.5),h.amounts.getFirst(),1e-10);assertEquals(WeaponRangeFalloffTest.loss(30,18.25,40,.5),h.amounts.getLast(),1e-10);assertEquals(9,h.state().ammunition().get("a").magazine());assertTrue(h.state().shotGroups().isEmpty());
    }
    @Test void duplicateSourcesUseTheGreatestFamilyBonusAndCannotBorrowAnotherOwnersGrant()throws Exception {
        var h=new Harness();var source=new EffectSource("duplicate","chorus_d2:ricochet_rounds","player",new BuffInstance.Origin("player","duplicate","a",""),Set.of("chorus:enhanced"),Map.of("ricochet_bounces",new Measure(3,Unit.COUNT)));
        h.session.start(0,SourceChange.bind(source));h.healthy();assertEquals(56,h.query("chorus_d2:weapon_range","a",50,Unit.STAT_POINT));assertEquals(61,h.query("chorus_d2:weapon_stability","a",50,Unit.STAT_POINT));assertEquals(3,h.query("chorus_d2:weapon_ricochet_grant","a",0,Unit.COUNT));
        var alien=new EffectSource("alien","chorus_d2:ricochet_rounds","other",new BuffInstance.Origin("other","alien","a",""),Set.of(),Map.of("ricochet_bounces",new Measure(9,Unit.COUNT)));h.session.start(0,SourceChange.bind(alien));h.healthy();assertEquals(3,h.query("chorus_d2:weapon_ricochet_grant","a",0,Unit.COUNT));
    }
    @Test void gearRequiresExplicitPositiveIntegralBouncePolicy()throws Exception {
        var h=new Harness();for(double bad:List.of(0d,-1d,.5,2147483648d)){
            var gear=new Loadout.Gear("bad","test:rifle",Map.of("magazine","normal"),Map.of("ricochet_bounces",new Measure(bad,Unit.COUNT)));
            assertThrows(RuntimeException.class,()->h.program.equipment().validate(new Loadout(Map.of("test:primary",gear),Optional.of("test:primary"))));
        }
    }
    @Test void definitionAndDomainRestoreKeepTheSelectedPerkAndQueryPolicy()throws Exception {
        var h=new Harness();var encoded=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.program.program()).getOrThrow();assertEquals(h.program.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,encoded).getOrThrow());
        h.bounces=2;h.equip("enhanced","none","primary");h.restore();h.fire();assertEquals(2,h.rays.getFirst().bounces());assertEquals(17.75,h.hits.getFirst().impact().number("range_start",Unit.METER).value());assertEquals(61,h.query("chorus_d2:weapon_stability","a",50,Unit.STAT_POINT));
    }
}
