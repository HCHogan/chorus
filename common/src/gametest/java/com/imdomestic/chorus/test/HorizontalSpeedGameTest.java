package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.network.HorizontalSpeedPayload;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class HorizontalSpeedGameTest {
    public static CompiledEffects program(){return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("horizontal_speed")).getOrThrow(),NativeMotionGameTest.program().program()));}
    public static EffectSource source(String id,LivingEntity actor,double speed){var source=NativeMovementGameTest.source(id,"speed",actor);return new EffectSource(source.instance(),source.bundle(),source.holder(),source.origin(),source.tags(),Map.of("speed",new Measure(speed,Unit.METER_PER_SECOND)));}
    static NativeRangedGameTest.Harness harness(GameTestHelper h){return new NativeRangedGameTest.Harness(h,program());}
    static double distance(Vec3 a,Vec3 b){return Math.hypot(a.x-b.x,a.z-b.z);}
    static void near(GameTestHelper h,double actual,double expected,String message){h.assertTrue(Math.abs(actual-expected)<1e-7,message+": "+actual+" != "+expected);}
    @GameCase(environment="chorus_gametest:horizontal_speed_budget")
    public void velocityAndEveryCoordinateWriteShareOneRadialBudget(GameTestHelper h){
        try(var t=harness(h)){
            var actor=t.mob(EntityTypes.COW,2,2);actor.setDeltaMovement(3,.2,4);t.runtime.bind(source("cap",actor,2));near(h,distance(actor.getDeltaMovement(),Vec3.ZERO),.1,"velocity ceiling");near(h,actor.getDeltaMovement().y,.2,"vertical velocity altered");
            var start=actor.position();actor.move(MoverType.SELF,new Vec3(.03,.2,.04));near(h,distance(start,actor.position()),.05,"short move changed");actor.setPos(actor.position().add(0,.2,3));near(h,actor.getZ()-start.z,.09,"raw write did not share remaining budget");
            var exhausted=actor.position();for(var cause:MoverType.values())actor.move(cause,new Vec3(1,0,1));actor.setPosRaw(exhausted.x+3,exhausted.y+.2,exhausted.z+4);near(h,distance(exhausted,actor.position()),0,"repeat write escaped tick budget");near(h,actor.getY()-start.y,.6,"vertical position was capped");
            t.runtime.bind(source("other",actor,2));t.runtime.unbind("cap");actor.move(MoverType.SELF,new Vec3(1,0,0));near(h,distance(exhausted,actor.position()),0,"source replacement replenished budget");
            t.runtime.unbind("other");actor.setPos(actor.position().add(1,0,0));near(h,actor.getX()-exhausted.x,1,"release retained cap");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:horizontal_speed_collision")
    public void collisionsPreserveUnusedTravelAndAxisLocksComposeWithImpulseReceipts(GameTestHelper h){
        try(var t=harness(h)){
            h.setBlock(3,40,2,Blocks.STONE);h.setBlock(3,41,2,Blocks.STONE);var actor=t.mob(EntityTypes.COW,2,2);actor.setPos(h.absoluteVec(new Vec3(3-actor.getBbWidth()/2.0,40,2.5)));var start=actor.position();t.runtime.bind(source("cap",actor,2));
            actor.move(MoverType.SELF,new Vec3(1,0,0));near(h,actor.getX(),start.x,"wall penetrated");actor.move(MoverType.SELF,new Vec3(0,0,.1));near(h,actor.getZ()-start.z,.1,"wall consumed unused movement");
            t.runtime.bind(NativeMotionGameTest.source("height","vertical",actor));var receipt=NativeMotionGameTest.impulse(t,actor,new com.imdomestic.chorus.effect.target.WorldDirection(t.level.dimension().identifier().toString(),1,1,0));near(h,receipt.requireChange().after().x(),2,"impulse receipt omitted speed ceiling");near(h,receipt.requireChange().after().y(),0,"impulse ignored height lock");
            t.runtime.bind(source("zero",actor,0));actor.setDeltaMovement(1,1,1);NativeMotionGameTest.same(h,actor.getDeltaMovement(),Vec3.ZERO,"zero cap plus vertical lock");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:horizontal_speed_tick",maxTicks=25)
    public void newTickResetsOnlyOneTickOfTravelAndIdleTimeCannotAccumulate(GameTestHelper h){
        var t=harness(h);try{var actor=t.mob(EntityTypes.COW,2,2);actor.setNoAi(true);t.runtime.bind(source("cap",actor,2));var start=actor.position();actor.move(MoverType.SELF,new Vec3(3,0,4));near(h,distance(start,actor.position()),.1,"diagonal extra travel");
            h.runAfterDelay(12,()->{try(t){var before=actor.position();actor.move(MoverType.SELF,new Vec3(10,0,0));near(h,distance(before,actor.position()),.1,"idle credit accumulated or tick did not reset");actor.move(MoverType.SELF,new Vec3(0,0,1));near(h,distance(before,actor.position()),.1,"second move reset tick");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:horizontal_speed_packets")
    public void forgedPacketsUseActualBudgetAndAuthorizedTeleportsPreserveRestrictions(GameTestHelper h)throws Exception{
        try(var t=harness(h)){var player=NativeMeleeGameTest.player(h);try{
            var spawn=h.absoluteVec(new Vec3(2.5,40,2.5));player.connection.teleport(spawn.x,spawn.y,spawn.z,0,0);player.connection.resetPosition();NativeMotionGameTest.acknowledge(player);t.runtime.bind(source("cap",player,2));var start=player.position();
            NativeMotionGameTest.move(player,start.add(2,0,0));near(h,player.getX()-start.x,.1,"forged packet not capped");var accepted=player.position();NativeMotionGameTest.move(player,accepted.add(1,0,1));near(h,distance(player.position(),accepted),0,"multiple client ticks replenished server budget");
            player.teleportRelative(3,2,1);near(h,distance(player.position(),accepted.add(3,2,1)),0,"host teleport blocked");player.connection.resetPosition();NativeMotionGameTest.acknowledge(player);accepted=player.position();NativeMotionGameTest.move(player,accepted.add(1,0,0));near(h,distance(player.position(),accepted),0,"teleport reset budget");near(h,MinecraftHorizontalSpeed.speed(player).orElseThrow(),2,"teleport cleared cap");t.settled();
        }finally{player.discard();}}h.succeed();
    }
    @GameCase(environment="chorus_gametest:horizontal_speed_expiry",maxTicks=18)
    public void recipientExpiryRestoresSourceCeilingAndCloseRemovesProjection(GameTestHelper h){
        var t=harness(h);try{var actor=t.mob(EntityTypes.COW,2,2);var caster=t.mob(EntityTypes.COW,5,2);t.runtime.bind(source("cap",actor,2));var source=NativeMovementGameTest.source("caster","speed_inputs",caster);t.runtime.bind(source);t.runtime.start(new RuleEngine.Signal("test:slow",new EffectEvent(source.holder(),actor.getUUID().toString(),source.origin(),Set.of(),Map.of())));near(h,MinecraftHorizontalSpeed.speed(actor).orElseThrow(),1,"recipient buff not projected");h.assertTrue(MinecraftHorizontalSpeed.speed(caster).isEmpty(),"caster slowed");
            h.runAfterDelay(8,()->{try(t){t.runtime.prepare();near(h,MinecraftHorizontalSpeed.speed(actor).orElseThrow(),2,"expiry removed independent source ceiling");t.settled();t.runtime.close();h.assertTrue(MinecraftHorizontalSpeed.speed(actor).isEmpty(),"close leaked cap");h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:horizontal_speed_failure")
    public void failureIsVisibleAndOldOwnersCannotClearNewerProjection(GameTestHelper h){
        try(var t=harness(h)){
            var actor=t.mob(EntityTypes.COW,2,2);boolean threw=false;try{t.runtime.bind(NativeMovementGameTest.source("broken","broken",actor));}catch(IllegalArgumentException expected){threw=true;}h.assertTrue(threw&&t.runtime.failure().isPresent(),"missing value silently ignored");near(h,MinecraftHorizontalSpeed.speed(actor).orElseThrow(),0,"query failure opened movement");h.assertTrue(t.runtime.nativeHorizontalSpeedReport().stream().anyMatch(r->r.error().isPresent()),"missing error evidence");
            var owner=new Object();var state=((MinecraftHorizontalSpeed.Synced)actor).chorus$horizontalSpeed();state.apply(owner,OptionalDouble.of(3),actor);t.runtime.close();near(h,MinecraftHorizontalSpeed.speed(actor).orElseThrow(),3,"old runtime cleared successor projection");state.apply(owner,OptionalDouble.empty(),actor);h.assertTrue(MinecraftHorizontalSpeed.speed(actor).isEmpty(),"owner could not clear cap");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:horizontal_speed_mount")
    public void applicationDismountsAndReleaseAllowsRidingAgain(GameTestHelper h){
        try(var t=harness(h)){var actor=t.mob(EntityTypes.ZOMBIE,2,2);var mount=t.mob(EntityTypes.PIG,2,2);h.assertTrue(actor.startRiding(mount,true,false),"fixture mount");t.runtime.bind(source("cap",actor,2));h.assertTrue(!actor.isPassenger()&&!actor.startRiding(mount,true,false),"vehicle bypass");t.runtime.unbind("cap");h.assertTrue(actor.startRiding(mount,true,false),"release did not restore riding");t.settled();}h.succeed();
    }
    @GameCase(environment="chorus_gametest:horizontal_speed_wire")
    public void wireDistinguishesClearZeroAndPositiveCeilingsAndRejectsNonfiniteValues(GameTestHelper h){
        for(var speed:List.of(OptionalDouble.empty(),OptionalDouble.of(0),OptionalDouble.of(2.5))){
            var payload=new HorizontalSpeedPayload(h.getLevel().dimension().identifier().toString(),12,UUID.randomUUID(),speed);var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());try{HorizontalSpeedPayload.CODEC.encode(buffer,payload);h.assertValueEqual(HorizontalSpeedPayload.CODEC.decode(buffer),payload,"wire roundtrip");}finally{buffer.release();}
        }
        for(double value:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}){boolean rejected=false;try{new HorizontalSpeedPayload("minecraft:overworld",1,UUID.randomUUID(),OptionalDouble.of(value));}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,"invalid wire speed accepted");}h.succeed();
    }
}
