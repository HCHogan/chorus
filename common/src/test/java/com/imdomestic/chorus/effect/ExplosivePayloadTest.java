package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Split physical damage, independent travel/radial factors and explicit synthetic weapon calibration. */
class ExplosivePayloadTest {
    static final String TAG="chorus_d2:explosive_payload_damage";
    static final List<String> PARTS=List.of("explosive_payload","explosive_payload_test_calibration","weapon_stats","weapon_range_curves","weapon_range_test_calibration");
    static CompiledEffects program(JsonObject data)throws Exception{
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        for(String n:PARTS)parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(n)).getOrThrow());return CompiledEffects.link(parts);
    }
    static Loadout.Gear gun(String id,String family,String perk){return new Loadout.Gear(id,family.equals("auto_rifle")?"test:rifle":"test:hand_cannon",Map.of("perk",perk));}
    static class Harness extends ChainReactionTest.Harness {
        Harness(boolean pvp)throws Exception{this(json("explosive_payload_weapon"),pvp);}
        Harness(JsonObject data,boolean pvp)throws Exception{this(program(data),TAG,pvp);}
        Harness(CompiledEffects program,String tag,boolean pvp){super(program,tag,pvp,Map.of(),Map.of());}
        @Override void equip(String a,String b,String drawn){equip(gun("a","hand_cannon",a),gun("b","auto_rifle",b),drawn);}
        void equip(Loadout.Gear a,Loadout.Gear b,String drawn){var next=new Loadout(Map.of("test:primary",a,"test:secondary",b),Optional.of("test:"+drawn));session.start(now(),new EquipmentChange("player",state().equipment().getOrDefault("player",Loadout.EMPTY),next).signal());healthy();}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){return switch(r.command()){
            case WeaponAim.Verify q -> new WeaponAim.Verified(q,true);
            default -> super.world(r);
        };}
        ProjectileFlight.Launch shot(){return shots.getLast();}
        void hit(double distance,boolean head){hit(shot(),distance,head);}
        void hit(ProjectileFlight.Launch shot,double distance,boolean head){
            var start=shot.position().orElseThrow();var point=new WorldPosition(start.dimension(),start.x()+distance,start.y(),start.z());entity("target",health.getOrDefault("target",1000d),point.x());
            if(head){var parameters=Map.of("min_x",new Measure(0,Unit.MULTIPLIER),"max_x",new Measure(1,Unit.MULTIPLIER),"min_y",new Measure(0,Unit.MULTIPLIER),"max_y",new Measure(1,Unit.MULTIPLIER),"min_z",new Measure(0,Unit.MULTIPLIER),"max_z",new Measure(1,Unit.MULTIPLIER));session.start(now(),SourceChange.bind(new EffectSource("region","test:weapon_region","target",new BuffInstance.Origin("target","region","",""),Set.of(),parameters)));healthy();}
            var bounds=new ProjectileFlight.Geometry(new WorldPosition(point.dimension(),point.x()-.3,point.y()-.5,point.z()-.3),.6,2,.6,.125);
            session.start(now(),shot.finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,point,Optional.of("target"),0,0,0,100_000).withGeometry(bounds)));healthy();
        }
        void block(ProjectileFlight.End end,double distance){var start=shot().position().orElseThrow();session.start(now(),shot().finish(new ProjectileFlight.Impact(end,new WorldPosition(start.dimension(),start.x()+distance,0,0),Optional.empty(),0,end==ProjectileFlight.End.BLOCK?1:0,0,100_000)));healthy();}
        double query(String profile,String weapon,double base){var event=new EffectEvent("player",weapon,new BuffInstance.Origin("player","query",weapon,""),Set.of(),Map.of());return program.calculate(state(),"player",event,profile,new Measure(base,Unit.STAT_POINT),List.of()).output().value();}
    }
    static double direct(double d,boolean head){return (head?15:5)*(WeaponRangeFalloffTest.loss(d,20.5,30.5,.33)/10);}
    @Test void twoModesPrecisionAndRangeKeepBlastIndependentFromTravelAndCrit()throws Exception{
        for(boolean pvp:List.of(false,true))for(boolean head:List.of(false,true))for(double d:new double[]{5,20.5,25.5,30.5,100}){
            var h=new Harness(pvp);h.fire();h.hit(d,head);assertEquals(2,h.hits.size());assertEquals(direct(d,head),h.amounts.get(0),1e-9);assertEquals(pvp?4.65:6.5,h.amounts.get(1),1e-9);
            assertEquals(1000-direct(d,head)-(pvp?4.65:6.5),h.health.get("target"),1e-9);
            assertEquals(head,h.hits.getFirst().tags().contains("chorus:precision"));assertFalse(h.bursts().getFirst().tags().contains("chorus:precision"));assertEquals(h.hits.getFirst().group(),h.bursts().getFirst().group());assertTrue(h.hits.getFirst().group().isPresent());
        }
    }
    @Test void pvpSevenPercentReductionRequiresBothLegendaryAndHandCannon()throws Exception{
        for(boolean hc:List.of(false,true))for(boolean legendary:List.of(false,true)){
            var data=json("explosive_payload_weapon");if(!legendary)for(var item:data.getAsJsonObject("equipment").getAsJsonArray("items")){var tags=item.getAsJsonObject().getAsJsonArray("tags");tags.remove(new JsonPrimitive("chorus_d2:legendary"));tags.add("chorus_d2:exotic");}
            var h=new Harness(data,true);h.equip(gun("a",hc?"hand_cannon":"auto_rifle","normal"),gun("b","auto_rifle","none"),"primary");h.fire();h.hit(5,false);assertEquals(hc&&legendary?4.65:5,h.amounts.getLast(),1e-10);
        }
    }
    @Test void primaryIsNotDoubleCountedAndNeighborsUseTheirOwnRadialFalloff()throws Exception{
        var h=new Harness(false);h.entity("near",1000,-3.5);h.entity("edge",1000,-2);h.entity("outside",1000,-1.99);h.fire();h.hit(5,false);
        assertEquals(988.5,h.health.get("target"));assertEquals(996.75,h.health.get("near"));assertEquals(1000,h.health.get("edge"));assertEquals(1000,h.health.get("outside"));assertEquals(2,h.bursts().size());assertEquals(3,h.queries.getFirst().radius());
    }
    @Test void lethalDirectStillExplodesButDoesNotDamageTheDeadPrimaryAgain()throws Exception{
        var h=new Harness(false);h.entity("target",4,-5);h.entity("near",6,-5);h.fire();h.hit(5,false);assertEquals(0,h.health.get("target"));assertEquals(0,h.health.get("near"));assertEquals(List.of("target","near"),h.hits.stream().map(DamageCommand::target).toList());assertTrue(h.bursts().getFirst().killTags().contains("chorus:weapon_kill"));
    }
    @Test void wallContactExplodesButExpiryAndUnloadDoNot()throws Exception{
        for(var end:List.of(ProjectileFlight.End.BLOCK,ProjectileFlight.End.EXPIRED,ProjectileFlight.End.UNLOADED)){
            var h=new Harness(false);h.entity("near",1000,-5);h.fire();h.block(end,5);assertEquals(end==ProjectileFlight.End.BLOCK?993.5:1000,h.health.get("near"));assertTrue(h.hits.stream().allMatch(d->d.tags().contains(TAG)));
        }
    }
    @Test void unequippedCapturedShotAndCheckpointKeepBothSourceMultipliersAndWeaponCredit()throws Exception{
        var h=new Harness(false);h.bind("bonus","test:payload_bonus","a");h.fire();var saved=EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(h.shot()),ProjectileFlight.Launch.class);
        h.session.start(h.now(),new EquipmentChange("player",h.state().equipment().get("player"),Loadout.EMPTY).signal());h.session.start(h.now(),SourceChange.remove("bonus"));h.restore();h.entity("player",1000,2000);h.hit(saved,25.5,true);
        assertEquals(direct(25.5,true)*1.5,h.amounts.getFirst(),1e-9);assertEquals(9.75,h.amounts.getLast(),1e-9);assertTrue(h.hits.stream().allMatch(d->d.source().weapon().equals("a")));
    }
    @Test void enhancedStatAndNoPerkWeaponRemainIsolated()throws Exception{
        var h=new Harness(false);assertEquals(50,h.query("chorus_d2:weapon_reload","a",50));assertEquals(55,h.query("chorus_d2:weapon_reload","b",50));
        h.session.start(h.now(),SourceChange.bind(new EffectSource("duplicate","chorus_d2:explosive_payload","player",new BuffInstance.Origin("player","duplicate","b",""),Set.of("chorus:enhanced"))));h.healthy();assertEquals(55,h.query("chorus_d2:weapon_reload","b",50));
        h.equip("none","enhanced","primary");h.fire();h.hit(5,false);assertEquals(List.of(10d),h.amounts);assertTrue(h.queries.isEmpty());assertTrue(h.bursts().isEmpty());
    }
    @Test void immuneBlastAndUnknownReceiptDoNotInventDeathsOrReplay()throws Exception{
        var immune=new Harness(false);immune.immune=true;immune.fire();immune.hit(5,false);assertEquals(995,immune.health.get("target"));
        var h=new Harness(false);h.fire();h.failBurst=true;assertThrows(IllegalStateException.class,()->h.hit(5,false));assertEquals(988.5,h.health.get("target"));assertThrows(IllegalStateException.class,()->h.until(200_000));assertEquals(2,h.hits.size());
    }
    @Test void oneUseBonusIsConsumedOnceAndSharedByTheTwoComponentsButNotTheNextShot()throws Exception{
        var h=new Harness(false);h.session.start(h.now(),new RuleEngine.Signal("test:arm",new EffectEvent("player","player",new BuffInstance.Origin("player","arm","a",""),Set.of(),Map.of())));h.healthy();h.fire();h.hit(5,false);
        assertEquals(List.of(10d,13d),h.amounts);assertTrue(h.state().buffs().instances().isEmpty());assertTrue(h.state().damageGroups().isEmpty());h.until(200_000);h.fire();h.hit(5,false);assertEquals(List.of(10d,13d,5d,6.5),h.amounts);
    }
    @Test void radialCalibrationMustBeProvided()throws Exception{
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("explosive_payload_weapon")).getOrThrow());for(String n:PARTS)if(!n.equals("explosive_payload_test_calibration"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(n)).getOrThrow());assertThrows(IllegalArgumentException.class,()->CompiledEffects.link(parts));
    }
}
