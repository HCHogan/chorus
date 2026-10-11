package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.CalculationActions;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

/** Real collision coordinates consume launch-time range traces; vertical shots stay in one loaded chunk. */
public class WeaponRangeFalloffGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h,String family,boolean perk,Consumer<JsonObject> edit)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"weapon_range_weapon",data->{RadiantGameTest.merge(data,"box_breathing","weapon_stats","weapon_range_curves","weapon_range_test_calibration","fail_deadly","fail_deadly_test_calibration","reload_insert_rounds");edit.accept(data);},true);
        try{
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+2.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+2.5);t.owner.setXRot(-90);t.owner.setYRot(0);t.owner.setGameMode(GameType.SURVIVAL);
            var params=Map.of("receipt_seconds",new Measure(1,Unit.SECOND),"enhanced_range",new Measure(18,Unit.STAT_POINT),"enhanced_aim_assist",new Measure(6,Unit.STAT_POINT));
            var gear=new Loadout.Gear("a",family.equals("auto_rifle")?"test:rifle":"test:hand_cannon",perk?Map.of("perk","none","fail_deadly","normal"):Map.of("perk","none"),params);
            var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),gear);t.owner.getInventory().setItem(0,stack);var e=PlayerEquipment.get(t.owner);e.swap(t.owner,"test:primary",0,e.revision());draw(t,"primary");bind(t,"test:aim_observer",true);return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static double endpoint(EffectProjectile shot,String key){return ((CalculationActions.PipelineResult)shot.launch().orElseThrow().continuation().bindings().get(key)).calculation().output().value();}
    static LivingEntity target(ProjectileGameTest.Harness t,WorldPosition muzzle,double distance){var target=t.cow(2.5,46,3.5);target.setPos(muzzle.x(),muzzle.y()+distance+.125,muzzle.z());return target;}
    static double expected(double distance,double start,double end,double floor){return 10*(1-(1-floor)*Math.max(0,Math.min(1,(distance-start)/(end-start))));}
    static void hit(ProjectileGameTest.Harness t,EffectProjectile shot,LivingEntity victim,double distance,double start,double end,double floor){
        int before=t.receipts.size();for(int i=0;i<35&&!shot.isRemoved();i++)shot.tick();
        t.h.assertTrue(t.runtime.failure().isEmpty(),"range runtime failed: "+t.runtime.failure());t.h.assertTrue(shot.isRemoved()&&t.receipts.size()==before+1,"shot did not hit exactly once");
        var command=t.hits.getLast();near(t.h,command.impact().number("distance",Unit.METER).value(),distance,"actual launch to collision separation");near(t.h,command.impact().number("range_start",Unit.METER).value(),start,"frozen start");near(t.h,command.impact().number("range_end",Unit.METER).value(),end,"frozen end");
        near(t.h,t.receipts.getLast().outgoing().orElseThrow().output().value(),expected(distance,start,end,floor),"physical falloff trace");near(t.h,victim.getHealth(),100-expected(distance,start,end,floor),"physical health after falloff");victim.discard();
    }
    static void hip(GameTestHelper h,String family)throws Exception{
        var t=open(h,family,false,_ -> {});try{
            double start=family.equals("auto_rifle")?17:20.5,end=family.equals("auto_rifle")?40:30.5,floor=family.equals("auto_rifle")?.5:.33;double[] distances={5,start,(start+end)/2,end,end+20};
            for(int i=0;i<distances.length;i++){final int index=i;at(t,1+3*i,()->{var shot=PugilistGameTest.fire(t);near(h,endpoint(shot,"range_start"),start,"hip start");var target=target(t,shot.launch().orElseThrow().position().orElseThrow(),distances[index]);hit(t,shot,target,distances[index],start,end,floor);if(index==distances.length-1){t.close();h.succeed();}});}
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:range_auto",maxTicks=35)
    public void autoRifleActualCollisionUsesItsPlateauInterpolationAndNonzeroFloor(GameTestHelper h)throws Exception{hip(h,"auto_rifle");}
    @GameCase(environment="chorus_gametest:range_hand_cannon",maxTicks=35)
    public void handCannonUsesDifferentEndpointsAndThirtyThreePercentFloor(GameTestHelper h)throws Exception{hip(h,"hand_cannon");}
    @GameCase(environment="chorus_gametest:range_ads",maxTicks=30)
    public void readyAdsRangeSurvivesReleaseUnequipAndOwnerMovementBeforeThePhysicalHit(GameTestHelper h)throws Exception{
        var t=open(h,"auto_rifle",false,_ -> {});try{WeaponAimGameTest.input(t,true);at(t,4,()->{try(t){settled(t);var shot=PugilistGameTest.fire(t);var muzzle=shot.launch().orElseThrow().position().orElseThrow();var victim=target(t,muzzle,40);near(h,endpoint(shot,"range_start"),27.2,"ready ADS range");
            WeaponAimGameTest.input(t,false);var e=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,ItemStack.EMPTY);e.swap(t.owner,"test:primary",0,e.revision());t.owner.setPos(t.owner.getX()+8,t.owner.getY()+8,t.owner.getZ());
            hit(t,shot,victim,40,27.2,64,.5);h.assertValueEqual(t.hits.getLast().source().weapon(),"a","removed weapon credit");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:range_fail_deadly",maxTicks=35)
    public void actualFailDeadlyHitExtendsTheFollowingWeaponsDamagePlateau(GameTestHelper h)throws Exception{
        var t=open(h,"auto_rifle",true,data->{var ammo=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition");ammo.addProperty("capacity",1);ammo.addProperty("magazine",1);});
        try{var first=PugilistGameTest.fire(t);hit(t,first,target(t,first.launch().orElseThrow().position().orElseThrow(),20),20,17,40,.5);near(h,FailDeadlyGameTest.stacks(t,"a"),1,"real damage grants the one-round full stack");TimelostMagazineGameTest.command(t,"chorus weapon reload");
            at(t,4,()->{try(t){settled(t);var second=PugilistGameTest.fire(t);near(h,endpoint(second,"range_start"),18.875,"+15 Range enters real start curve");near(h,endpoint(first,"range_start"),17,"old shot trace stayed fixed");hit(t,second,target(t,second.launch().orElseThrow().position().orElseThrow(),20),20,18.875,40,.5);h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    static JsonObject projectile(JsonObject data){for(var step:data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire"))if(step.getAsJsonObject().has("projectile"))return step.getAsJsonObject().getAsJsonObject("projectile");throw new AssertionError();}
    @GameCase(environment="chorus_gametest:range_pierce",maxTicks=25)
    public void piercingContactsUseSeparateDistancesFromTheSameCapturedMuzzle(GameTestHelper h)throws Exception{
        try(var t=open(h,"auto_rifle",false,data->projectile(data).add("collision",JsonParser.parseString("{\"entity_pierces\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"count\"}}")))){
            var shot=PugilistGameTest.fire(t);var muzzle=shot.launch().orElseThrow().position().orElseThrow();var first=target(t,muzzle,20);var second=target(t,muzzle,30);
            for(int i=0;i<35&&!shot.isRemoved();i++)shot.tick();h.assertTrue(t.runtime.failure().isEmpty(),"piercing range failure: "+t.runtime.failure());h.assertValueEqual(t.hits.size(),2,"two physical contacts");
            near(h,t.hits.get(0).impact().number("distance",Unit.METER).value(),20,"first separation");near(h,t.hits.get(1).impact().number("distance",Unit.METER).value(),30,"second separation does not restart at the first target");near(h,first.getHealth(),100-expected(20,17,40,.5),"first actual damage");near(h,second.getHealth(),100-expected(30,17,40,.5),"second actual damage");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:range_unknown",maxTicks=25)
    public void unknownReceiptRetainsAppliedFalloffDamageWithoutReplayingContact(GameTestHelper h)throws Exception{
        try(var t=open(h,"auto_rifle",false,_ -> {})){var shot=PugilistGameTest.fire(t);var victim=target(t,shot.launch().orElseThrow().position().orElseThrow(),28.5);t.failAfterDamage=true;
            for(int i=0;i<35&&!shot.isRemoved();i++)shot.tick();h.assertTrue(t.runtime.failure().isPresent(),"unknown damage must stop the runtime");near(h,victim.getHealth(),92.5,"already applied midpoint damage remains");h.assertTrue(shot.isRemoved(),"unknown contact is consumed");int count=t.operations.size();shot.tick();t.runtime.prepare();h.assertValueEqual(t.operations.size(),count,"unknown contact replayed work");near(h,victim.getHealth(),92.5,"damage applied twice");h.succeed();}
    }
}
