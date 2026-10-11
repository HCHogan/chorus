package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeaponRangeFalloffTest {
    static final List<String> PARTS=List.of("box_breathing","weapon_stats","weapon_range_curves","weapon_range_test_calibration","fail_deadly","fail_deadly_test_calibration","reload_insert_rounds");
    static CompiledEffects program(JsonObject data)throws Exception{
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        for(String n:PARTS)parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(n)).getOrThrow());return CompiledEffects.link(parts);
    }
    static Loadout.Gear gun(String id,String family,boolean perk){return new Loadout.Gear(id,family.equals("auto_rifle")?"test:rifle":"test:hand_cannon",perk?Map.of("perk","none","fail_deadly","normal"):Map.of("perk","none"),FailDeadlyTest.calibration());}
    static class Harness extends ChainReactionTest.Harness {
        Harness(boolean pvp)throws Exception{this(json("weapon_range_weapon"),pvp);}
        Harness(JsonObject data,boolean pvp)throws Exception{super(program(data),"test:unused",pvp,FailDeadlyTest.calibration(),FailDeadlyTest.calibration());bind("aim-observer","test:aim_observer","");}
        @Override void equip(String a,String b,String drawn){equip(gun("a","auto_rifle",false),gun("b","hand_cannon",false),drawn);}
        void equip(Loadout.Gear a,Loadout.Gear b,String drawn){var next=new Loadout(Map.of("test:primary",a,"test:secondary",b),Optional.of("test:"+drawn));session.start(now(),new EquipmentChange("player",state().equipment().getOrDefault("player",Loadout.EMPTY),next).signal());healthy();}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){return switch(r.command()){
            case WeaponAim.Verify q -> new WeaponAim.Verified(q,true);
            case WeaponReload.Verify q -> new WeaponReload.Verified(q,true);
            default -> super.world(r);
        };}
        void aim(boolean b){session.start(now(),new WeaponAim.Request("player","aim/"+ ++sequence,b).signal());healthy();}
        ProjectileFlight.Launch shot(){return shots.getLast();}
        void hit(ProjectileFlight.Launch shot,double distance){
            var start=shot.position().orElseThrow();var point=new WorldPosition(start.dimension(),start.x()+distance,start.y(),start.z());entity("target",1000,point.x());
            var bounds=new ProjectileFlight.Geometry(new WorldPosition(point.dimension(),point.x()-.3,point.y()-.5,point.z()-.3),.6,2,.6,.125);
            session.start(now(),shot.finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,point,Optional.of("target"),0,0,0,100_000).withGeometry(bounds)));healthy();
        }
        void nextFire(){until(now()+200_000);fire();}
        double result(ProjectileFlight.Launch shot,String name){return ((CalculationActions.PipelineResult)shot.continuation().bindings().get(name)).calculation().output().value();}
    }
    static double start(String family,double range){return family.equals("auto_rifle")?10.75+.125*range:16+.09*range;}
    static double end(String family,double range){return family.equals("auto_rifle")?40:29+.03*range;}
    static double floor(String family){return family.equals("auto_rifle")?.5:.33;}
    static double loss(double distance,double start,double end,double floor){return 10*(1-(1-floor)*Math.max(0,Math.min(1,(distance-start)/(end-start))));}
    @Test void bothModesAndFamiliesConsumeHipOrReadyAdsEndpointsAtEachBoundary()throws Exception{
        for(boolean pvp:List.of(false,true))for(String family:List.of("auto_rifle","hand_cannon"))for(boolean ads:List.of(false,true)){
            var h=new Harness(pvp);h.equip(gun("a",family,false),gun("b","auto_rifle",false),"primary");double zoom=ads?(family.equals("auto_rifle")?1.6:1.5):1;
            if(ads){h.aim(true);h.until(h.state().aiming().get("player").dueAt());}
            double a=start(family,50)*zoom,b=end(family,50)*zoom;
            for(double d:new double[]{0,a,(a+b)/2,b,b+50}){h.fire();var shot=h.shot();assertEquals(a,h.result(shot,"range_start"),1e-10);assertEquals(b,h.result(shot,"range_end"),1e-10);h.hit(shot,d);assertEquals(loss(d,a,b,floor(family)),h.amounts.getLast(),1e-9);assertEquals(d,h.hits.getLast().impact().number("distance",Unit.METER).value(),1e-9);h.until(h.now()+200_000);}
        }
    }
    @Test void firingBeforeReadyAndAfterReleaseUsesNewStateButAcceptedFlightRetainsItsAdsRange()throws Exception{
        var h=new Harness(false);h.aim(true);h.fire();assertEquals(17,h.result(h.shot(),"range_start"));h.until(200_000);h.fire();var accepted=h.shot();assertEquals(27.2,h.result(accepted,"range_start"),1e-10);
        h.aim(false);h.until(400_000);h.fire();assertEquals(17,h.result(h.shot(),"range_start"));h.hit(accepted,30);assertEquals(loss(30,27.2,64,.5),h.amounts.getLast(),1e-10);
    }
    @Test void rangePointsClampBeforeDifferentEndpointCurves()throws Exception{
        var h=new Harness(false);
        for(String family:List.of("auto_rifle","hand_cannon"))for(double base:new double[]{-20,0,50,100,150})for(String edge:List.of("start","end")){
            var query=new EffectEvent("player","a",new com.imdomestic.chorus.effect.buff.BuffInstance.Origin("player","query","a",""),Set.of(),Map.of());
            var result=h.program.calculatePipeline(h.state(),"player",query,List.of("chorus_d2:weapon_range","chorus_d2:"+family+"_falloff_"+edge),new Measure(base,Unit.STAT_POINT));double clamped=Math.max(0,Math.min(100,base));
            assertEquals(edge.equals("start")?start(family,clamped):end(family,clamped),result.output().value(),1e-10);
        }
    }
    @Test void actualFailDeadlyDamageChangesTheNextShotWithoutRewritingAlreadyCapturedEndpoints()throws Exception{
        var data=json("weapon_range_weapon");var ammo=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition");ammo.addProperty("capacity",1);ammo.addProperty("magazine",1);
        var h=new Harness(data,false);h.equip(gun("a","auto_rifle",true),gun("b","hand_cannon",false),"primary");h.fire();var old=h.shot();h.hit(old,20);assertEquals(loss(20,17,40,.5),h.amounts.getLast(),1e-10);
        h.session.start(h.now(),new WeaponReload.Request("player","reload").signal());h.until(h.now()+200_000);h.fire();var improved=h.shot();assertEquals(18.875,h.result(improved,"range_start"),1e-10);assertEquals(17,h.result(old,"range_start"));h.hit(improved,20);assertEquals(loss(20,18.875,40,.5),h.amounts.getLast(),1e-10);
        h.equip(gun("a","auto_rifle",true),gun("b","hand_cannon",false),"secondary");h.fire();assertEquals(20.5,h.result(h.shot(),"range_start"));
    }
    @Test void storedLaunchAndDomainRestoreKeepOriginAndCalculationAfterOwnerMovesAndSourceRemoval()throws Exception{
        var h=new Harness(false);h.aim(true);h.until(200_000);h.fire();var shot=h.shot();var saved=EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(shot),ProjectileFlight.Launch.class);assertEquals(shot,saved);
        h.session.start(h.now(),new EquipmentChange("player",h.state().equipment().get("player"),Loadout.EMPTY).signal());h.restore();h.entity("player",1000,1000);h.hit(saved,40);
        assertEquals(40,h.hits.getLast().impact().number("distance",Unit.METER).value());assertEquals(loss(40,27.2,64,.5),h.amounts.getLast(),1e-10);assertEquals("a",h.hits.getLast().source().weapon());
    }
    @Test void missingDistanceCalibrationAndInvalidLexicalPositionAreCompileErrors()throws Exception{
        var data=json("weapon_range_weapon");var bad=data.toString().replace("\"from\":\"muzzle\"","\"from\":\"not_captured\"");assertThrows(IllegalArgumentException.class,()->program(com.google.gson.JsonParser.parseString(bad).getAsJsonObject()));
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());for(String n:PARTS)if(!n.equals("weapon_range_test_calibration"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(n)).getOrThrow());assertThrows(IllegalArgumentException.class,()->CompiledEffects.link(parts));
    }
}
