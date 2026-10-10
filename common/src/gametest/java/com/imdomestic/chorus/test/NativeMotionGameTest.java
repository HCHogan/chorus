package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class NativeMotionGameTest {
    public static CompiledEffects program(){return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("native_motion")).getOrThrow();}
    public static EffectSource source(String instance,String bundle,LivingEntity actor){return NativeMovementGameTest.source(instance,bundle,actor);}
    static NativeRangedGameTest.Harness harness(GameTestHelper h){return new NativeRangedGameTest.Harness(h,program());}
    static void bind(NativeRangedGameTest.Harness t,LivingEntity actor,String bundle){t.runtime.bind(source(bundle,bundle,actor));}
    static Vec3 anchor(LivingEntity actor){return ((MinecraftMovementInput.Synced)actor).chorus$movementAnchor();}
    static void same(GameTestHelper h,Vec3 actual,Vec3 expected,String what){h.assertTrue(actual.distanceTo(expected)<1e-7,what+": "+actual+" != "+expected);}
    static Impulse.Receipt impulse(NativeRangedGameTest.Harness t,LivingEntity actor,WorldDirection direction){
        var world=new MinecraftWorldActions(t.level,id->id.equals(actor.getUUID().toString())?actor:null,_ -> t.level.damageSources().generic(),(_,_) -> true,_ -> {});
        var command=new Impulse.Command(actor.getUUID().toString(),Optional.of(direction),10,Impulse.Scale.ONE,source("external","motion",actor).origin(),Set.of());
        return (Impulse.Receipt)world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1,0,0),command));
    }
    @GameCase(environment="chorus_gametest:native_motion_all")
    public void fullConstraintsBlockDirectVelocityRawPositionAndAllMovementCauses(GameTestHelper h){
        try(var t=harness(h)){
            for(var type:List.of(EntityTypes.COW,EntityTypes.GHAST,EntityTypes.PHANTOM,EntityTypes.ENDER_DRAGON)){
                var actor=t.mob(type,2,2);var start=actor.position();actor.setDeltaMovement(1,-.2,1);bind(t,actor,"motion");same(h,anchor(actor),start,"initial authoritative anchor");same(h,actor.getDeltaMovement(),Vec3.ZERO,"old velocity was not cleared");
                actor.setDeltaMovement(2,-3,4);actor.addDeltaMovement(new Vec3(4,5,6));same(h,actor.getDeltaMovement(),Vec3.ZERO,"direct velocity bypass");
                for(var cause:MoverType.values()){actor.move(cause,new Vec3(.2,.3,.4));same(h,actor.position(),start,"move bypass: "+cause);}
                actor.setPosRaw(start.x+1,start.y+1,start.z+1);same(h,actor.position(),start,"raw coordinate bypass");actor.setPos(start.add(2,2,2));same(h,actor.position(),start,"normal coordinate bypass");
                t.runtime.unbind("motion");actor.setDeltaMovement(.1,-.2,.3);same(h,actor.getDeltaMovement(),new Vec3(.1,-.2,.3),"released velocity");actor.setPos(start.add(1,1,1));same(h,actor.position(),start.add(1,1,1),"released coordinates");actor.discard();
            }t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:native_motion_height")
    public void heightLockKeepsHorizontalMotionAndCannotStepThroughASlab(GameTestHelper h){
        try(var t=harness(h)){
            for(int x=0;x<7;x++)for(int z=0;z<5;z++)h.setBlock(x,39,z,Blocks.STONE);h.setBlock(3,40,2,Blocks.STONE_SLAB);
            var actor=t.mob(EntityTypes.ZOMBIE,2,2);actor.setOnGround(true);var start=actor.position();bind(t,actor,"vertical");actor.setNoGravity(false);
            actor.setDeltaMovement(.2,-3,.1);same(h,actor.getDeltaMovement(),new Vec3(.2,0,.1),"vertical-only velocity filter");h.assertValueEqual(actor.maxUpStep(),0f,"height lock allowed step-up");
            actor.move(MoverType.SELF,new Vec3(2,0,0));HealingGameTest.near(h,actor.getY(),start.y,"height changed at slab");h.assertTrue(actor.getBoundingBox().maxX<=h.absolutePos(new net.minecraft.core.BlockPos(3,40,2)).getX()+1e-6,"disabled step entered slab");h.assertTrue(actor.getX()>start.x,"unlocked horizontal axis was stopped");
            t.runtime.unbind("vertical");h.assertTrue(actor.maxUpStep()>0,"step height did not recover");actor.setDeltaMovement(0,-.2,0);h.assertTrue(actor.getDeltaMovement().y<0,"gravity axis did not recover");t.settled();
        }h.succeed();
    }
    static void acknowledge(ServerPlayer player)throws Exception{
        var f=player.connection.getClass().getDeclaredField("awaitingTeleport");f.setAccessible(true);
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(f.getInt(player.connection),player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot()));
    }
    static void move(ServerPlayer player,Vec3 requested)throws Exception{
        player.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);player.connection.resetPosition();player.connection.tick();player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(requested.x,requested.y,requested.z,27,13,false,false));acknowledge(player);
    }
    @GameCase(environment="chorus_gametest:native_motion_packets")
    public void forgedPositionPacketsAreCorrectedWhileFreeAxesStillReceiveVanillaValidation(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var player=NativeMeleeGameTest.player(h);try{
                player.setPos(h.absoluteVec(new Vec3(2.5,40,2.5)));var start=player.position();bind(t,player,"motion");move(player,start.add(.2,.2,.2));same(h,player.position(),start,"small client displacement escaped full constraint");HealingGameTest.near(h,player.getYRot(),27,"camera yaw was blocked");
                t.runtime.unbind("motion");bind(t,player,"vertical");move(player,start.add(.2,.2,0));HealingGameTest.near(h,player.getX(),start.x+.2,"allowed axis not accepted");HealingGameTest.near(h,player.getY(),start.y,"forged height accepted");var accepted=player.position();
                move(player,accepted.add(1000,1,0));same(h,player.position(),accepted,"constraint bypassed native speed validation on free axis");
                var floating=player.connection.getClass().getDeclaredField("clientIsFloating");var ticks=player.connection.getClass().getDeclaredField("aboveGroundTickCount");floating.setAccessible(true);ticks.setAccessible(true);floating.setBoolean(player.connection,true);ticks.setInt(player.connection,100);player.connection.tick();h.assertValueEqual(ticks.getInt(player.connection),0,"intentional vertical hold accrued fly kick time");t.settled();
            }finally{player.discard();}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:native_motion_impulse")
    public void impulseReceiptsObserveClampedAxesWithoutFalseSuccessOrGrace(GameTestHelper h){
        try(var t=harness(h)){
            var actor=t.mob(EntityTypes.COW,2,2);bind(t,actor,"motion");actor.syncVelocity=false;var direction=new WorldDirection(t.level.dimension().identifier().toString(),1,1,0);var full=impulse(t,actor,direction);
            h.assertValueEqual(full.outcome(),Impulse.Outcome.UNCHANGED,"blocked impulse falsely applied");h.assertTrue(!full.requireChange().changed()&&!actor.syncVelocity&&!actor.isInPostImpulseGraceTime(),"blocked impulse emitted motion or grace");
            t.runtime.unbind("motion");bind(t,actor,"vertical");var partial=impulse(t,actor,direction);h.assertValueEqual(partial.outcome(),Impulse.Outcome.APPLIED,"partial impulse rejected");HealingGameTest.near(h,partial.requireChange().delta().y(),0,"receipt retained rejected vertical component");h.assertTrue(partial.requireChange().delta().x()>0&&actor.getDeltaMovement().y==0,"horizontal impulse missing");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:native_motion_expiry",maxTicks=18)
    public void expiryRetainsIndependentAxesAndReleasedForcesDoNotAccumulate(GameTestHelper h){
        var t=harness(h);try{
            var actor=t.mob(EntityTypes.COW,2,2);var caster=t.mob(EntityTypes.COW,5,2);var source=source("inputs","motion_inputs",caster);t.runtime.bind(source);bind(t,actor,"horizontal");
            t.runtime.start(new RuleEngine.Signal("test:lock",new EffectEvent(source.holder(),actor.getUUID().toString(),source.origin(),Set.of(),Map.of())));var start=actor.position();
            for(int i=0;i<10;i++)actor.addDeltaMovement(new Vec3(1,1,1));same(h,actor.getDeltaMovement(),Vec3.ZERO,"blocked force accumulated");
            h.runAfterDelay(8,()->{try(t){t.runtime.prepare();h.assertValueEqual(NativeMovementGameTest.mask(actor),4,"expiry cleared source horizontal lock");actor.move(MoverType.SELF,new Vec3(1,-.2,1));HealingGameTest.near(h,actor.getY(),start.y-.2,"released vertical axis did not move");HealingGameTest.near(h,actor.getX(),start.x,"remaining axis moved");t.runtime.unbind("horizontal");same(h,actor.getDeltaMovement(),Vec3.ZERO,"released a bank of blocked forces");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:native_motion_teleport")
    public void explicitAbsoluteAndRelativeTeleportsReanchorWithoutRemovingRestrictions(GameTestHelper h){
        try(var t=harness(h)){
            var actor=t.mob(EntityTypes.COW,2,2);bind(t,actor,"motion");var target=actor.position().add(2,3,1);actor.teleportTo(target.x,target.y,target.z);same(h,actor.position(),target,"host mob teleport blocked");same(h,anchor(actor),target,"mob anchor did not move");actor.move(MoverType.SELF,new Vec3(1,1,1));same(h,actor.position(),target,"mob moved after reanchor");
            t.runtime.unbind("motion");var player=NativeMeleeGameTest.player(h);try{
                player.setPos(h.absoluteVec(new Vec3(2.5,40,2.5)));bind(t,player,"motion");var before=player.position();player.teleportRelative(2,3,1);same(h,player.position(),before.add(2,3,1),"relative host teleport");same(h,anchor(player),player.position(),"player anchor did not move");h.assertValueEqual(NativeMovementGameTest.mask(player),12,"teleport discarded constraint");t.settled();
            }finally{player.discard();}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:native_motion_riding")
    public void constraintDismountsRecipientsAndRejectsRemountUntilReleased(GameTestHelper h){
        try(var t=harness(h)){
            var actor=t.mob(EntityTypes.ZOMBIE,2,2);var mount=t.mob(EntityTypes.PIG,2,2);h.assertTrue(actor.startRiding(mount,true,false),"fixture mount");bind(t,actor,"motion");h.assertTrue(!actor.isPassenger(),"anchored passenger remained attached to a moving vehicle");h.assertTrue(!actor.startRiding(mount,true,false),"forced riding bypassed anchor");t.runtime.unbind("motion");h.assertTrue(actor.startRiding(mount,true,false),"riding failed after release");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:native_motion_ai",maxTicks=100)
    public void ghastDirectVelocityAiCannotMoveTheAnchorAndResumesAfterRelease(GameTestHelper h){
        var t=harness(h);try{
            var actor=h.spawn(EntityTypes.GHAST,2,50,2);t.mobs.add(actor);var start=actor.position();actor.getMoveControl().setWantedPosition(start.x+20,start.y+3,start.z+20,1);bind(t,actor,"motion");
            h.runAfterDelay(20,()->{try{same(h,actor.position(),start,"direct-velocity AI bypassed constraint");h.assertTrue(!actor.isNoAi(),"constraint disabled entire AI");t.runtime.unbind("motion");actor.getMoveControl().setWantedPosition(start.x+20,start.y+3,start.z+20,1);}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(65,()->{try(t){h.assertTrue(actor.position().distanceTo(start)>.2,"direct-velocity AI did not resume");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
