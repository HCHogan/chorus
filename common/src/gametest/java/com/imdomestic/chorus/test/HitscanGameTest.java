package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class HitscanGameTest {
    static void instant(JsonObject data) {
        var body=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire");
        var ray=body.get(4).getAsJsonObject(); ray.remove("projectile");
        ray.add("hitscan",JsonParser.parseString("{\"position\":\"muzzle\",\"direction\":\"aim\",\"range\":{\"type\":\"chorus:constant\",\"value\":20,\"unit\":\"meter\"}}"));
    }
    static ProjectileGameTest.Harness open(GameTestHelper h)throws Exception { return PrecisionAmmoGameTest.open(h,HitscanGameTest::instant); }
    static void shot(ProjectileGameTest.Harness t,boolean precision) {
        var target=PrecisionAmmoGameTest.target(t,precision);int before=t.hits.size();
        t.h.assertValueEqual(t.runtime.fire(t.owner).outcome(),WeaponFire.Outcome.ACCEPTED,"instant shot acceptance");
        t.h.assertValueEqual(t.hits.size(),before+1,"damage must finish without waiting for an entity tick");
        near(t.h,target.getHealth(),precision?985:990,"ray geometry selects actual damage");
        t.h.assertValueEqual(t.hits.getLast().tags().contains("chorus:precision"),precision,"target-owned precision region");
        t.h.assertTrue(t.projectiles.isEmpty(),"hitscan spawned a flying entity");t.runtime.unbind("region/"+target.getUUID());target.discard();
    }
    @GameCase(environment="chorus_gametest:hitscan_perks",maxTicks=50)
    public void instantPrecisionShotsGenerateTripleTapAndFourthTimeAmmunition(GameTestHelper h)throws Exception {
        var t=open(h);try { shot(t,true);
            for(int i=1;i<4;i++){int index=i;RampageGameTest.at(t,i*4,()->{shot(t,true);h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),12-(index+1)+(index+1)/3+2*((index+1)/4),"confirmed precision ammunition");});}
            RampageGameTest.at(t,16,()->{try(t){shot(t,false);near(h,PrecisionAmmoGameTest.count(t,PrecisionAmmoGameTest.TT,"a"),1,"body hit excluded");near(h,PrecisionAmmoGameTest.count(t,PrecisionAmmoGameTest.FT,"a"),0,"independent fourth counter");h.succeed();}});
        } catch(Exception|Error e){t.close();throw e;}
    }
    static Hitscan.Query query(ProjectileGameTest.Harness t,WorldPosition p,WorldDirection d,double range,double inflation) {
        String id=t.owner.getUUID().toString();return new Hitscan.Query(Optional.of(p),Optional.of(d),range,inflation,id,id,Optional.empty());
    }
    static WorldPosition point(ProjectileGameTest.Harness t,Vec3 p){return new WorldPosition(t.h.getLevel().dimension().identifier().toString(),p.x,p.y,p.z);}
    static WorldDirection forward(ProjectileGameTest.Harness t){return new WorldDirection(t.h.getLevel().dimension().identifier().toString(),0,0,1);}
    static Hitscan.Result trace(ProjectileGameTest.Harness t,Hitscan.Query q){return MinecraftHitscan.trace(t.h.getLevel(),q);}
    @GameCase(environment="chorus_gametest:hitscan_geometry")
    public void nearestActualBoundsWinAndWallBeforeThemBlocksTheRay(GameTestHelper h)throws Exception {
        try(var t=open(h)) {
            var target=PrecisionAmmoGameTest.target(t,true);var farther=PrecisionAmmoGameTest.target(t,true);farther.setPos(target.position().add(0,0,2));
            var q=query(t,point(t,t.owner.getEyePosition()),forward(t),20,0);var hit=trace(t,q);
            h.assertValueEqual(hit.targets().getFirst().entity(),target.getUUID().toString(),"nearest intersection");
            near(h,hit.contact().orElseThrow().relativeY(),.9,"actual uninflated contact geometry");
            var wall=BlockPos.containing(t.owner.getX(),t.owner.getEyeY(),target.getZ()-1);t.blocks.put(wall,h.getLevel().getBlockState(wall));h.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());
            h.assertValueEqual(trace(t,q).outcome(),Hitscan.Outcome.BLOCK,"wall blocks target");
            h.assertValueEqual(t.runtime.fire(t.owner).outcome(),WeaponFire.Outcome.ACCEPTED,"wall shot is still paid");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),11,"blocked shot payment");h.assertTrue(t.hits.isEmpty(),"wall shot fabricated entity damage");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:hitscan_endpoint")
    public void closedRangeEndpointAndExplicitInflationHaveDistinctGeometry(GameTestHelper h)throws Exception {
        try(var t=open(h)) {
            var target=PrecisionAmmoGameTest.target(t,false);var start=t.owner.getEyePosition();double range=target.getBoundingBox().minZ-start.z;
            var q=query(t,point(t,start),forward(t),range,0);h.assertValueEqual(trace(t,q).outcome(),Hitscan.Outcome.ENTITY,"exact closed endpoint");
            h.assertValueEqual(trace(t,query(t,point(t,start),forward(t),range-.01,0)).outcome(),Hitscan.Outcome.RANGE,"out of range");
            double side=target.getBoundingBox().maxX+.05;var shifted=new Vec3(side,start.y,start.z);
            h.assertValueEqual(trace(t,query(t,point(t,shifted),forward(t),20,0)).outcome(),Hitscan.Outcome.RANGE,"no implicit hitbox inflation");
            var expanded=trace(t,query(t,point(t,shifted),forward(t),20,.1));h.assertValueEqual(expanded.outcome(),Hitscan.Outcome.ENTITY,"explicit inflation");h.assertTrue(expanded.contact().orElseThrow().relativeX()>1,"inflated contact must not clamp to real bounds");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:hitscan_slab")
    public void slabUsesItsCollisionShapeAndFluidsDoNotBlock(GameTestHelper h)throws Exception {
        try(var t=open(h)) {
            var target=PrecisionAmmoGameTest.target(t,false);var p=BlockPos.containing(t.owner.getX(),t.owner.getEyeY(),target.getZ()-1);
            t.blocks.put(p,h.getLevel().getBlockState(p));h.getLevel().setBlockAndUpdate(p,Blocks.STONE_SLAB.defaultBlockState());
            var below=new Vec3(t.owner.getX(),p.getY()+.25,t.owner.getZ());var above=new Vec3(t.owner.getX(),p.getY()+.75,t.owner.getZ());
            h.assertValueEqual(trace(t,query(t,point(t,below),forward(t),20,0)).outcome(),Hitscan.Outcome.BLOCK,"lower half slab");
            h.assertTrue(trace(t,query(t,point(t,above),forward(t),20,0)).outcome()!=Hitscan.Outcome.BLOCK,"empty upper half must not block");
            h.getLevel().setBlockAndUpdate(p,Blocks.WATER.defaultBlockState());h.assertTrue(trace(t,query(t,point(t,below),forward(t),20,0)).outcome()!=Hitscan.Outcome.BLOCK,"fluid ignored");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:hitscan_group")
    public void realThreeMemberRayGroupResolvesAfterThreeNativeDamageReceipts(GameTestHelper h)throws Exception {
        try(var t=new ProjectileGameTest.Harness(h,"hitscan",_->{},true)) {
            t.owner.setYRot(0);t.owner.setXRot(0);var target=LightweightKnifeGameTest.target(t,.5,100);
            t.fire();h.assertTrue(t.runtime.failure().isEmpty(),"ray group runtime failed: "+t.runtime.failure());near(h,target.getHealth(),70,"three actual damage receipts");
            h.assertValueEqual(t.hits.size(),3,"three members");h.assertTrue(t.cues.stream().anyMatch(c->c.cue().equals("test:all_hit")),"resolved summary missing");h.assertTrue(t.runtime.state().engine().domain().shotGroups().isEmpty(),"finished group remains live");h.assertTrue(t.projectiles.isEmpty(),"instant group spawned entities");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:hitscan_unloaded")
    public void unknownTerrainAndMissingCapturesNeverLoadOrInventAContact(GameTestHelper h)throws Exception {
        try(var t=open(h)) {
            var unknown=point(t,new Vec3(1_000_001,100,1_000_001));var q=query(t,unknown,forward(t),20,0);
            h.assertValueEqual(trace(t,q).outcome(),Hitscan.Outcome.UNLOADED,"unknown origin");
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(1_000_001>>4,1_000_001>>4)==null,"ray loaded terrain");
            h.assertValueEqual(trace(t,new Hitscan.Query(Optional.empty(),q.direction(),20,0,q.owner(),q.emitter(),Optional.empty())).outcome(),Hitscan.Outcome.MISSING_POSITION,"missing position");
            h.assertValueEqual(trace(t,new Hitscan.Query(q.position(),Optional.empty(),20,0,q.owner(),q.emitter(),Optional.empty())).outcome(),Hitscan.Outcome.MISSING_DIRECTION,"missing direction");
            h.assertValueEqual(trace(t,query(t,new WorldPosition("test:other",0,0,0),forward(t),20,0)).outcome(),Hitscan.Outcome.WRONG_DIMENSION,"dimension mismatch");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:hitscan_prefix")
    public void aKnownContactBeforeUnknownTerrainSurvivesTheUnavailableEndpoint(GameTestHelper h)throws Exception {
        try(var t=open(h)) {
            int x=t.owner.chunkPosition().getMinBlockX()>>4,z=t.owner.chunkPosition().getMinBlockZ()>>4;
            int count=0;while(h.getLevel().getChunkSource().getChunkNow(x+1,z)!=null && count++<512)x++;
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(x,z)!=null && h.getLevel().getChunkSource().getChunkNow(x+1,z)==null,"need loaded-to-unknown edge");
            double startX=x*16+8,startZ=z*16+8;
            var target=PrecisionAmmoGameTest.target(t,false);target.setPos(startX+4,128,startZ);
            var p=point(t,new Vec3(startX,128+target.getBbHeight()/2,startZ));
            var direction=new WorldDirection(p.dimension(),1,0,0);var q=query(t,p,direction,20,0);
            h.assertValueEqual(trace(t,q).outcome(),Hitscan.Outcome.ENTITY,"unavailable end erased earlier entity");target.discard();
            var empty=trace(t,q);h.assertValueEqual(empty.outcome(),Hitscan.Outcome.UNLOADED,"unknown terrain became a wall or miss");
            near(h,empty.position().orElseThrow().x(),(x+1)*16,"first unavailable boundary");
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(x+1,z)==null,"prefix scan loaded missing chunk");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:hitscan_unknown")
    public void unknownDamageKeepsAcceptedAmmoAndStopsWithoutReplay(GameTestHelper h)throws Exception {
        try(var t=open(h)) {
            var target=PrecisionAmmoGameTest.target(t,true);t.failAfterDamage=true;
            try{t.runtime.fire(t.owner);}catch(IllegalStateException expected){}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown damage must stop runtime");near(h,target.getHealth(),985,"world write already happened");
            h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),11,"accepted ammo retained");
            try{t.runtime.fire(t.owner);}catch(IllegalStateException expected){}
            h.assertValueEqual(t.hits.size(),1,"failed boundary replayed damage");h.assertTrue(t.projectiles.isEmpty(),"no physical projectile");h.succeed();
        }
    }
}
