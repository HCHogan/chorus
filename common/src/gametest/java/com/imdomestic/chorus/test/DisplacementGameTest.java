package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Synthetic 1.05 m lift in 0.25 m steps; these numbers are not a Destiny Suspend calibration. */
public class DisplacementGameTest {
    public static CompiledEffects program(){return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("displacement")).getOrThrow();}
    public static EffectSource source(LivingEntity actor){return NativeMovementGameTest.source("displacement","displacement",actor);}
    public static void event(MinecraftEffectRuntime runtime,EffectSource source,LivingEntity actor,String type){runtime.start(new RuleEngine.Signal(type,new EffectEvent(source.holder(),actor.getUUID().toString(),source.origin(),Set.of(),Map.of())));}
    static final class Harness implements AutoCloseable {
        final GameTestHelper h;final MinecraftWorldActions world;final MinecraftEffectRuntime runtime;
        final List<LivingEntity> actors=new ArrayList<>();final List<Displacement.Receipt> receipts=new ArrayList<>();final Map<BlockPos,BlockState> blocks=new HashMap<>();boolean fail;
        Harness(GameTestHelper h){
            this.h=h;world=new MinecraftWorldActions(h.getLevel(),id->actors.stream().filter(a->a.getUUID().toString().equals(id)).findFirst().orElse(null),_->h.getLevel().damageSources().generic(),(_,_) -> true,_ -> {});
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),r->{var result=world.apply(r);if(result instanceof Displacement.Receipt receipt){receipts.add(receipt);if(fail)throw new IllegalStateException("unknown displacement outcome");}return result;},MinecraftEffectRuntime::nativeSource);
        }
        LivingEntity cow(double x,double z){var actor=h.spawnWithNoFreeWill(EntityTypes.COW,new Vec3(x,40,z));actor.setNoGravity(true);actors.add(actor);return actor;}
        void block(int x,int y,int z){block(x,y,z,Blocks.STONE);}
        void block(int x,int y,int z,net.minecraft.world.level.block.Block block){var p=h.absolutePos(new BlockPos(x,y,z));blocks.putIfAbsent(p,h.getLevel().getBlockState(p));h.getLevel().setBlockAndUpdate(p,block.defaultBlockState());}
        Displacement.Receipt apply(LivingEntity actor,double x,double y,double z,double distance){return apply(actor,Optional.of(new WorldDirection(h.getLevel().dimension().identifier().toString(),x,y,z)),distance);}
        Displacement.Receipt apply(LivingEntity actor,Optional<WorldDirection> direction,double distance){return (Displacement.Receipt)world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99,0,0),new Displacement.Command(actor.getUUID().toString(),direction,distance,source(actor).origin(),Set.of())));}
        EffectSource lift(LivingEntity caster,LivingEntity target){var source=source(caster);runtime.bind(source);event(runtime,source,target,"test:lift");return source;}
        void settled(){h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"displacement runtime failed: "+runtime.failure());}
        @Override public void close(){runtime.close();actors.forEach(Entity::discard);blocks.forEach((p,b)->h.getLevel().setBlockAndUpdate(p,b));}
    }
    @GameCase(environment="chorus_gametest:displacement_ceiling")
    public void fullBoundingBoxClipsAtCeilingAndReanchorsAnExistingHold(GameTestHelper h){
        try(var t=new Harness(h)){
            var actor=t.cow(2.5,2.5);var caster=t.cow(6.5,2.5);for(int x=1;x<=4;x++)for(int z=1;z<=4;z++)t.block(x,42,z);
            t.lift(caster,actor);var before=actor.position();double clearance=h.absolutePos(new BlockPos(2,42,2)).getY()-actor.getBoundingBox().maxY;
            var receipt=t.apply(actor,0,1,0,2);h.assertValueEqual(receipt.outcome(),Displacement.Outcome.APPLIED,"clipped move");h.assertTrue(receipt.clipped(),"ceiling not reported");near(h,receipt.requireChange().delta().y(),clearance,"bbox clearance");near(h,actor.getY(),before.y+clearance,"actual lifted feet");
            NativeMotionGameTest.same(h,NativeMotionGameTest.anchor(actor),actor.position(),"new constrained anchor");h.assertValueEqual(NativeMovementGameTest.mask(actor),12,"relocation removed hold");actor.move(MoverType.SELF,new Vec3(0,-1,0));near(h,actor.getY(),before.y+clearance,"anchor still used old height");
            var blocked=t.apply(actor,0,1,0,2);h.assertValueEqual(blocked.outcome(),Displacement.Outcome.UNCHANGED,"zero clearance claimed success");h.assertTrue(blocked.clipped(),"blocked clip flag");near(h,blocked.requireChange().delta().length(),0,"blocked position delta");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:displacement_wall")
    public void wallAndSlabClipTheWholeBodyWithoutStepUpOrChangingVelocity(GameTestHelper h){
        try(var t=new Harness(h)){
            var actor=t.cow(2.5,2.5);for(int x=1;x<=4;x++)t.block(x,40,4,Blocks.STONE_SLAB);var before=actor.position();double clearance=h.absolutePos(new BlockPos(2,40,4)).getZ()-actor.getBoundingBox().maxZ;
            actor.setDeltaMovement(.1,.2,.3);actor.setOnGround(true);var receipt=t.apply(actor,0,0,1,5);h.assertTrue(receipt.clipped(),"body passed wall");near(h,actor.getZ(),before.z+clearance,"wall clearance");near(h,actor.getY(),before.y,"collision query stepped up");NativeMotionGameTest.same(h,actor.getDeltaMovement(),new Vec3(.1,.2,.3),"relocation replaced velocity");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:displacement_rejection")
    public void ineligibleTargetsInvalidDirectionsAndHostBudgetsDoNotMoveTheEntity(GameTestHelper h){
        try(var t=new Harness(h)){
            var actor=t.cow(2.5,2.5);var mount=t.cow(2.5,2.5);var before=actor.position();
            h.assertValueEqual(t.apply(actor,Optional.empty(),1).outcome(),Displacement.Outcome.MISSING_DIRECTION,"missing axis");h.assertValueEqual(t.apply(actor,Optional.of(new WorldDirection("minecraft:the_nether",0,1,0)),1).outcome(),Displacement.Outcome.WRONG_DIMENSION,"dimension mismatch");
            h.assertValueEqual(t.apply(actor,0,1,0,65).outcome(),Displacement.Outcome.QUERY_TOO_LARGE,"step budget");actor.noPhysics=true;h.assertValueEqual(t.apply(actor,0,1,0,1).outcome(),Displacement.Outcome.NO_PHYSICS,"noclip");actor.noPhysics=false;
            h.assertTrue(actor.startRiding(mount,true,false),"mount fixture");h.assertValueEqual(t.apply(actor,0,1,0,1).outcome(),Displacement.Outcome.PASSENGER,"passenger");h.assertValueEqual(t.apply(mount,0,1,0,1).outcome(),Displacement.Outcome.VEHICLE,"vehicle");actor.stopRiding();actor.setPos(before);
            actor.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE).setBaseValue(16);actor.refreshDimensions();h.assertValueEqual(t.apply(actor,1,1,1,60).outcome(),Displacement.Outcome.QUERY_TOO_LARGE,"swept volume budget");actor.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE).setBaseValue(1);actor.refreshDimensions();
            actor.teleportTo(1_000_008,actor.getY(),1_000_008);var far=actor.position();int cx=actor.blockPosition().getX()>>4,cz=actor.blockPosition().getZ()>>4;h.assertTrue(h.getLevel().getChunkSource().getChunkNow(cx,cz)==null,"fixture destination already loaded");h.assertValueEqual(t.apply(actor,0,1,0,1).outcome(),Displacement.Outcome.UNLOADED,"unloaded sweep");NativeMotionGameTest.same(h,actor.position(),far,"rejection moved actor");h.assertTrue(h.getLevel().getChunkSource().getChunkNow(cx,cz)==null,"collision query loaded a chunk");actor.setHealth(0);h.assertValueEqual(t.apply(actor,0,1,0,1).outcome(),Displacement.Outcome.DEAD,"dead actor");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:displacement_player")
    public void playerRelocationUsesNativeTeleportAcknowledgementAndPreservesKnownVelocity(GameTestHelper h)throws Exception{
        try(var t=new Harness(h)){
            var actor=NativeMeleeGameTest.player(h);t.actors.add(actor);var spawn=h.absoluteVec(new Vec3(2.5,40,2.5));actor.connection.teleport(spawn.x,spawn.y,spawn.z,0,0);actor.connection.resetPosition();NativeMotionGameTest.acknowledge(actor);var initial=actor.position();actor.connection.handleClientTickEnd(net.minecraft.network.protocol.game.ServerboundClientTickEndPacket.INSTANCE);actor.connection.resetPosition();actor.connection.tick();actor.connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(initial.x+.1,initial.y+.2,initial.z+.3,37,-19,false,false));var velocity=actor.getKnownMovement();NativeMotionGameTest.same(h,velocity,new Vec3(.1,.2,.3),"native player movement fixture");var start=actor.position();
            var receipt=t.apply(actor,0,1,0,.5);h.assertValueEqual(receipt.outcome(),Displacement.Outcome.APPLIED,"real player move");NativeMotionGameTest.same(h,actor.getDeltaMovement(),velocity,"known velocity at command completion");NativeMotionGameTest.acknowledge(actor);NativeMotionGameTest.same(h,actor.position(),start.add(0,.5,0),"acknowledged destination");near(h,actor.getYRot(),37,"view yaw");near(h,actor.getXRot(),-19,"view pitch");
            t.lift(actor,actor);t.apply(actor,0,1,0,.5);NativeMotionGameTest.acknowledge(actor);NativeMotionGameTest.same(h,NativeMotionGameTest.anchor(actor),start.add(0,1,0),"player relocation anchor");h.assertValueEqual(NativeMovementGameTest.mask(actor),12,"player hold lost");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:displacement_lifetime",maxTicks=120)
    public void periodicLiftUsesActualDistanceStopsAtHeightAndExpiryReleasesGravity(GameTestHelper h){
        var t=new Harness(h);try{
            var actor=t.cow(2.5,2.5);var caster=t.cow(7.5,2.5);var start=actor.position();var source=t.lift(caster,actor);actor.setNoGravity(false);
            h.runAfterDelay(8,()->{try{t.runtime.prepare();near(h,actor.getY(),start.y+1.05,"full staged lift");h.assertValueEqual(t.receipts.size(),5,"final short step or stop");h.assertTrue(t.receipts.stream().allMatch(r->r.command().origin().equals(source.origin())),"recipient replaced caster credit");near(h,t.receipts.getLast().command().distance(),.05,"remaining height step");t.settled();}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(50,()->{try{h.assertValueEqual(t.receipts.size(),5,"completed rise continued moving");near(h,actor.getY(),start.y+1.05,"hold drifted");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(108,()->{try(t){t.runtime.prepare();h.assertValueEqual(NativeMovementGameTest.mask(actor),0,"expiry retained movement restriction");h.assertTrue(actor.getY()<start.y+.85,"gravity did not resume");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:displacement_clip_timer",maxTicks=25)
    public void clippedRiseStopsItsTimerAndCleansingReleasesTheCeilingHold(GameTestHelper h){
        var t=new Harness(h);try{
            var actor=t.cow(2.5,2.5);var caster=t.cow(7.5,2.5);for(int x=1;x<=4;x++)for(int z=1;z<=4;z++)t.block(x,42,z);var source=t.lift(caster,actor);actor.setNoGravity(false);var height=new double[1];
            h.runAfterDelay(8,()->{try{t.runtime.prepare();h.assertValueEqual(t.receipts.size(),3,"ceiling did not stop periodic movement");h.assertTrue(t.receipts.getLast().clipped(),"partial last step missing");height[0]=actor.getY();event(t.runtime,source,actor,"test:release");h.assertValueEqual(NativeMovementGameTest.mask(actor),0,"cleanse retained hold");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(18,()->{try(t){h.assertValueEqual(t.receipts.size(),3,"timer ran after cleanse");h.assertTrue(actor.getY()<height[0]-.2,"released actor did not fall");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:displacement_unknown")
    public void uncertainWorldCompletionStopsWithoutRepeatingAnAppliedRelocation(GameTestHelper h){
        try(var t=new Harness(h)){
            var actor=t.cow(2.5,2.5);var source=source(actor);t.runtime.bind(source);var start=actor.position();t.fail=true;
            var event=new EffectEvent(source.holder(),source.holder(),source.origin(),Set.of(),Map.of("x",new com.imdomestic.chorus.stat.Measure(0,com.imdomestic.chorus.stat.Unit.MULTIPLIER),"y",new com.imdomestic.chorus.stat.Measure(1,com.imdomestic.chorus.stat.Unit.MULTIPLIER),"z",new com.imdomestic.chorus.stat.Measure(0,com.imdomestic.chorus.stat.Unit.MULTIPLIER),"distance",new com.imdomestic.chorus.stat.Measure(.5,com.imdomestic.chorus.stat.Unit.METER)));
            try{t.runtime.start(new RuleEngine.Signal("test:move",event));}catch(IllegalStateException expected){}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown receipt did not stop runtime");near(h,actor.getY(),start.y+.5,"world mutation was lost");try{t.runtime.prepare();}catch(IllegalStateException expected){}h.assertValueEqual(t.receipts.size(),1,"uncertain command replayed");
        }h.succeed();
    }
}
