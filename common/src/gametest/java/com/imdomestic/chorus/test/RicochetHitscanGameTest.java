package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class RicochetHitscanGameTest {
    /** Reuse a real equipment consumer; only synthetic ray policy and travel-distance inputs differ. */
    static void travel(JsonElement value){
        if(value.isJsonArray()){value.getAsJsonArray().forEach(RicochetHitscanGameTest::travel);return;}if(!value.isJsonObject())return;var o=value.getAsJsonObject();
        if(o.has("type") && o.get("type").getAsString().equals("chorus:position_distance")){o.remove("from");o.remove("to");o.addProperty("type","chorus:result");o.addProperty("binding","impact");o.addProperty("field","distance");return;}
        o.entrySet().forEach(e->travel(e.getValue()));
    }
    static ProjectileGameTest.Harness open(GameTestHelper h,int bounces,boolean hitOrigin,String variant)throws Exception {return open(h,bounces,hitOrigin,variant,1);}
    static ProjectileGameTest.Harness open(GameTestHelper h,int bounces,boolean hitOrigin,String variant,int hitsPerTarget)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"armor_piercing_weapon",d->{
            RadiantGameTest.merge(d,"armor_piercing_rounds","weapon_ballistics","weapon_stats","weapon_range_curves","weapon_range_test_calibration","armor_piercing_test_targets");
            var steps=d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire");var ray=steps.get(steps.size()-1).getAsJsonObject();
            ray.add("bounces",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":"+bounces+",\"unit\":\"count\"}"));ray.addProperty("hit_origin_after_bounce",hitOrigin);ray.add("hits_per_target",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":"+hitsPerTarget+",\"unit\":\"count\"}"));ray.getAsJsonObject("hitscan").getAsJsonObject("range").addProperty("value",40);travel(ray);
            for(var v:steps){var step=v.getAsJsonObject();if(step.has("action") && step.getAsJsonObject("action").get("type").getAsString().equals("chorus:calculate_pipeline"))step.getAsJsonObject("action").getAsJsonObject("input").addProperty("value",0);}
            ray.getAsJsonArray("do").add(JsonParser.parseString("{\"if\":{\"type\":\"chorus:result_flag\",\"binding\":\"impact\",\"field\":\"bounced\"},\"then\":[{\"type\":\"chorus:play_cue\",\"cue\":\"test:ricochet\"}]}"));
            for(var value:d.getAsJsonArray("bundles"))if(value.getAsJsonObject().get("id").getAsString().equals("test:projectile"))value.getAsJsonObject().add("rules",JsonParser.parseString("""
                [{"id":"one_target","on":"chorus:shot_resolved","if":{"type":"chorus:all","of":[
                  {"type":"chorus:compare","op":"eq","left":{"type":"chorus:event_number","name":"targets_hit","unit":"count"},"right":{"type":"chorus:constant","value":1,"unit":"count"}},
                  {"type":"chorus:compare","op":"eq","left":{"type":"chorus:event_number","name":"pellets_hit","unit":"count"},"right":{"type":"chorus:constant","value":1,"unit":"count"}}]},"do":[{"type":"chorus:play_cue","cue":"test:one_target"}]}]
                """));

        },true);
        try{t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+2.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+2.5);t.owner.setYRot(-45);t.owner.setXRot(0);t.owner.setGameMode(GameType.SURVIVAL);t.owner.getFoodData().setFoodLevel(0);t.owner.setHealth(100);ArmorPiercingRoundsGameTest.equip(t,"a","primary",variant);PugilistGameTest.draw(t,"primary");return t;}
        catch(Exception|Error error){t.close();throw error;}
    }
    static void block(ProjectileGameTest.Harness t,BlockPos p){t.blocks.putIfAbsent(p,t.h.getLevel().getBlockState(p));t.h.getLevel().setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());}
    static BlockPos wall(ProjectileGameTest.Harness t){var p=BlockPos.containing(t.owner.getX()+7.5,t.owner.getEyeY(),t.owner.getZ()+7.5);for(int z=-1;z<=1;z++)block(t,p.offset(0,0,z));return p;}
    static LivingEntity target(ProjectileGameTest.Harness t,double x,double z){var v=t.cow(2.5,50,2.5);v.setPos(t.owner.getX()+x,t.owner.getEyeY()-v.getBbHeight()/2,t.owner.getZ()+z);return v;}
    static RicochetHitscan.Query query(ProjectileGameTest.Harness t,WorldPosition p,WorldDirection d,double range,int pierces,int bounces,boolean origin){return new RicochetHitscan.Query(HitscanGameTest.query(t,p,d,range,0),pierces,bounces,origin);}
    @GameCase(environment="chorus_gametest:ricochet_seam")
    public void largeCoordinateBlockSeamsReflectFromTheExposedFaceInBothOrientations(GameTestHelper h)throws Exception {
        for(boolean rotate:List.of(false,true))try(var t=open(h,1,false,"normal")){
            double x=rotate?-1056701.5:9380754.5,z=rotate?9380754.5:-1056701.5;
            // Explicit fixture preparation, not production query loading.
            h.getLevel().getChunk(((int)Math.floor(x))>>4,((int)Math.floor(z))>>4);t.owner.setPos(x,128,z);
            var wall=BlockPos.containing(x+7.5,t.owner.getEyeY(),z+7.5);
            for(int n=-1;n<=1;n++)block(t,rotate?wall.offset(n,0,0):wall.offset(0,0,n));
            var p=HitscanGameTest.point(t,t.owner.getEyePosition());var look=t.owner.getLookAngle();
            // These distant fixture chunks are readable but not entity-simulated; geometry must not
            // pretend relocated mobs are queryable. Existing simulated scenes verify real damage.
            var path=MinecraftHitscan.trace(h.getLevel(),query(t,p,new WorldDirection(p.dimension(),look.x,look.y,look.z),12,0,1,false));
            var face=path.contacts().getFirst().observed().orElseThrow();near(h,rotate?face.normalZ():face.normalX(),-1,"exposed face normal");
            h.assertValueEqual(path.contacts().size(),2,"seam added a phantom contact");h.assertValueEqual(path.contacts().getLast().outcome(),Hitscan.Outcome.RANGE,"short reflected path should remain loaded");near(h,path.contacts().getLast().distance(),12,"seam reset travel budget");
            var direction=path.contacts().getLast().query().direction().orElseThrow();h.assertTrue(rotate?direction.z()<0 && direction.x()>0:direction.x()<0 && direction.z()>0,"reflected direction differs from wall orientation");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:ricochet_damage")
    public void obliqueWallReflectionUsesCumulativeTravelForTwoActualDamageContacts(GameTestHelper h)throws Exception {
        try(var t=open(h,1,false,"normal")){wall(t);var first=target(t,5,10);var second=target(t,3,12);ArmorPiercingRoundsGameTest.fire(t);
            h.assertValueEqual(t.hits.size(),2,"wall plus two entities");double half=first.getBbWidth()/2.0,firstDistance=(10-half)*Math.sqrt(2),secondDistance=(12-half)*Math.sqrt(2);
            near(h,t.hits.getFirst().impact().number("distance",Unit.METER).value(),firstDistance,"first full polyline length");near(h,t.hits.getLast().impact().number("distance",Unit.METER).value(),secondDistance,"second full polyline length");
            near(h,first.getHealth(),100-WeaponRangeFalloffGameTest.expected(firstDistance,11.375,40,.5),"first real falloff");near(h,second.getHealth(),100-WeaponRangeFalloffGameTest.expected(secondDistance,11.375,40,.5),"second real falloff");
            h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"single payment");h.assertTrue(PrecisionAmmoGameTest.state(t).shotGroups().isEmpty(),"reflected member did not finish");h.assertTrue(t.projectiles.isEmpty(),"instant reflection spawned flight");h.succeed();}
    }
    @GameCase(environment="chorus_gametest:ricochet_zero")
    public void zeroBouncesLeaveTheRearTargetUntouched(GameTestHelper h)throws Exception {
        try(var t=open(h,0,false,"normal")){wall(t);var target=target(t,5,10);ArmorPiercingRoundsGameTest.fire(t);near(h,target.getHealth(),100,"zero bounce cannot turn corner");h.assertTrue(t.hits.isEmpty(),"zero bounce damage");h.assertTrue(t.cues.isEmpty(),"zero bounce cue");h.assertTrue(PrecisionAmmoGameTest.state(t).shotGroups().isEmpty(),"wall member leaked");h.succeed();}
    }
    @GameCase(environment="chorus_gametest:ricochet_origin")
    public void originCollisionIsExcludedInitiallyAndExplicitlyEnabledAfterReflection(GameTestHelper h)throws Exception {
        for(boolean enabled:List.of(false,true))try(var t=open(h,1,enabled,"none")){
            t.owner.setYRot(0);block(t,BlockPos.containing(t.owner.getX(),t.owner.getEyeY(),t.owner.getZ()+4.5));ArmorPiercingRoundsGameTest.fire(t);
            near(h,t.owner.getHealth(),enabled?90:100,"reflected origin policy");h.assertValueEqual(t.hits.size(),enabled?1:0,"origin contact count");if(enabled)h.assertValueEqual(t.hits.getFirst().target(),t.owner.getUUID().toString(),"self hit keeps real identity");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"self hit pays only once");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:ricochet_budget")
    public void repeatedWallsSpendOneSharedRangeBudgetAndDoNotRehitAnEarlierEntity(GameTestHelper h)throws Exception {
        try(var t=open(h,1,false,"none")){
            t.owner.setPos(t.owner.getX(),t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+8.5);var p=HitscanGameTest.point(t,t.owner.getEyePosition());
            block(t,BlockPos.containing(p.x(),p.y(),p.z()+3.5));block(t,BlockPos.containing(p.x(),p.y(),p.z()-4.5));
            var d=new WorldDirection(p.dimension(),0,0,1);var target=target(t,0,1.5);var q=query(t,p,d,20,Integer.MAX_VALUE,Integer.MAX_VALUE,false);var result=MinecraftHitscan.trace(h.getLevel(),q);
            near(h,result.contacts().getLast().distance(),20,"shared path range");h.assertValueEqual(result.contacts().getLast().outcome(),Hitscan.Outcome.RANGE,"large budget must end at range");h.assertValueEqual(result.contacts().stream().filter(c->c.outcome()==Hitscan.Outcome.ENTITY).count(),1L,"crossed entity hit twice");h.assertValueEqual(result.contacts().getLast().bounces(),3L,"three geometric reflections");
            var limited=MinecraftHitscan.trace(h.getLevel(),query(t,p,d,20,Integer.MAX_VALUE,2,false));h.assertValueEqual(limited.contacts().getLast().outcome(),Hitscan.Outcome.BLOCK,"bounce budget exhausted");h.assertValueEqual(limited.contacts().getLast().bounces(),2L,"last stop is not a bounce");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:ricochet_revisit")
    public void configuredReturnThroughTheSameTargetDamagesTwiceWithoutDuplicatingPelletCredit(GameTestHelper h)throws Exception {
        try(var t=open(h,1,false,"normal",2)){t.owner.setYRot(0);var victim=target(t,0,2);block(t,BlockPos.containing(t.owner.getX(),t.owner.getEyeY(),t.owner.getZ()+4.5));ArmorPiercingRoundsGameTest.fire(t);
            near(h,victim.getHealth(),80,"outward and returning damage");h.assertValueEqual(t.hits.size(),2,"two actual receipts");h.assertValueEqual(t.cues.stream().filter(c->c.cue().equals("test:one_target")).count(),1L,"one pellet and one distinct target");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"one payment for return path");h.assertTrue(PrecisionAmmoGameTest.state(t).shotGroups().isEmpty(),"return contact leaked member");h.succeed();}
    }
    @GameCase(environment="chorus_gametest:ricochet_geometry")
    public void capturedReflectionSurvivesWallRemovalAndTargetMotionDuringEarlierActions(GameTestHelper h)throws Exception {
        try(var t=open(h,1,false,"normal")){var wall=wall(t);var first=target(t,5,10);var second=target(t,3,12);t.onCue=cue->{if(cue.cue().equals("test:ricochet")){for(int z=-1;z<=1;z++)h.getLevel().setBlockAndUpdate(wall.offset(0,0,z),Blocks.AIR.defaultBlockState());first.setPos(first.position().add(5,0,0));}};
            t.afterDamageReceipt=(damage,receipt)->second.setPos(second.position().add(5,0,0));ArmorPiercingRoundsGameTest.fire(t);
            h.assertValueEqual(t.hits.size(),2,"reflection re-observed geometry");h.assertTrue(first.getHealth()<100 && second.getHealth()<100,"captured targets lost their damage");h.assertTrue(h.getLevel().getBlockState(wall).isAir(),"wall did not move");h.succeed();}
    }
    @GameCase(environment="chorus_gametest:ricochet_unknown")
    public void unknownBounceActionKeepsThePaymentAndStopsBeforeAnyCapturedTargetDamage(GameTestHelper h)throws Exception {
        try(var t=open(h,1,false,"normal")){wall(t);var victim=target(t,5,10);t.failAfterCue=true;try{t.runtime.fire(t.owner);}catch(IllegalStateException expected){}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown cue did not fail");near(h,victim.getHealth(),100,"continued after unknown bounce action");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"accepted payment");h.assertValueEqual(t.cues.size(),1,"bounce cue executed");
            try{t.runtime.prepare();t.runtime.fire(t.owner);}catch(IllegalStateException expected){}h.assertValueEqual(t.cues.size(),1,"unknown cue replayed");h.assertTrue(t.hits.isEmpty(),"failed path damaged target");h.succeed();}
    }
    @GameCase(environment="chorus_gametest:ricochet_embedded")
    public void embeddedOriginStopsWhileAnExactOutwardFaceDoesNotCreateAPhantomSecondBounce(GameTestHelper h)throws Exception {
        try(var t=open(h,1,false,"none")){var p=BlockPos.containing(t.owner.getEyePosition());block(t,p);String dimension=h.getLevel().dimension().identifier().toString();var inside=new WorldPosition(dimension,p.getX()+.5,p.getY()+.5,p.getZ()+.5);var d=new WorldDirection(dimension,1,0,0);
            var result=MinecraftHitscan.trace(h.getLevel(),query(t,inside,d,10,0,10,false));h.assertValueEqual(result.contacts().size(),1,"embedded origin reflected");h.assertTrue(result.contacts().getFirst().embedded(),"embedded evidence lost");near(h,result.contacts().getFirst().distance(),0,"embedded distance");
            var edge=new WorldPosition(dimension,p.getX(),p.getY()+.5,p.getZ()+.5);var face=MinecraftHitscan.trace(h.getLevel(),query(t,edge,d,3,0,10,false));h.assertValueEqual(face.contacts().size(),2,"face generated repeated zero-length contacts");h.assertValueEqual(face.contacts().getLast().bounces(),1L,"exact face reflected once");near(h,face.contacts().getLast().distance(),3,"zero-distance bounce preserved total travel");h.succeed();}
    }
    @GameCase(environment="chorus_gametest:ricochet_unloaded")
    public void reflectedUnknownBoundaryIsNotAnotherWallAndNeverLoadsTheMissingChunk(GameTestHelper h)throws Exception {
        try(var t=open(h,1,false,"none")){int x=t.owner.chunkPosition().getMinBlockX()>>4,z=t.owner.chunkPosition().getMinBlockZ()>>4;int scanned=0;while(h.getLevel().getChunkSource().getChunkNow(x+1,z)!=null && scanned++<512)x++;
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(x,z)!=null && h.getLevel().getChunkSource().getChunkNow(x+1,z)==null,"loaded edge unavailable");String dimension=h.getLevel().dimension().identifier().toString();var p=new WorldPosition(dimension,x*16+12,128.5,z*16+8.5);block(t,BlockPos.containing(p.x()-2,p.y(),p.z()));
            var q=query(t,p,new WorldDirection(dimension,-1,0,0),20,0,100,false);var result=MinecraftHitscan.trace(h.getLevel(),q);h.assertValueEqual(result.contacts().size(),2,"unknown boundary reflected");h.assertValueEqual(result.contacts().getLast().outcome(),Hitscan.Outcome.UNLOADED,"unknown boundary classification");h.assertValueEqual(result.contacts().getLast().bounces(),1L,"unknown boundary added bounce");near(h,result.contacts().getLast().position().orElseThrow().x(),(x+1)*16,"unknown chunk edge");h.assertTrue(h.getLevel().getChunkSource().getChunkNow(x+1,z)==null,"reflection loaded a chunk");h.succeed();}
    }
}
