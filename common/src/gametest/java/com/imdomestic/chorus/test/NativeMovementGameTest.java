package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.tags.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;

public class NativeMovementGameTest {
    public static CompiledEffects program(){return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("native_movement")).getOrThrow();}
    public static EffectSource source(String instance,String bundle,LivingEntity actor){String holder=actor.getUUID().toString();return new EffectSource(instance,"test:"+bundle,holder,new BuffInstance.Origin(holder,instance,"",""),Set.of());}
    public static int mask(LivingEntity actor){return ((MinecraftMovementInput.Synced)actor).chorus$movementRestrictions();}
    static NativeRangedGameTest.Harness harness(GameTestHelper h){return new NativeRangedGameTest.Harness(h,program());}
    static void guard(NativeRangedGameTest.Harness t,LivingEntity actor){t.runtime.bind(source("move","movement_guard",actor));t.runtime.bind(source("jump","jump_guard",actor));}

    @GameCase(environment="chorus_gametest:native_movement_velocity")
    public void inputDenialPreservesVelocityGravityAndRealImpulseCommands(GameTestHelper h){
        try(var t=harness(h)){
            var actor=t.mob(EntityTypes.ZOMBIE,2,2);actor.setYRot(0);actor.setDeltaMovement(.2,0,.1);guard(t,actor);
            actor.moveRelative(1,new Vec3(0,0,1));h.assertValueEqual(actor.getDeltaMovement(),new Vec3(.2,0,.1),"denied input changed existing velocity");
            var world=new MinecraftWorldActions(t.level,id->id.equals(actor.getUUID().toString())?actor:null,_ -> t.level.damageSources().generic(),(_,_) -> true,_ -> {});
            var command=new Impulse.Command(actor.getUUID().toString(),Optional.of(new WorldDirection(t.level.dimension().identifier().toString(),1,0,0)),10,Impulse.Scale.ONE,source("external","movement_guard",actor).origin(),Set.of());
            var receipt=(Impulse.Receipt)world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1,0,0),command));h.assertValueEqual(receipt.outcome(),Impulse.Outcome.APPLIED,"restriction blocked external impulse");
            HealingGameTest.near(h,actor.getDeltaMovement().x,.7,"real impulse retained previous velocity");actor.setNoGravity(false);actor.setOnGround(false);var before=actor.position();actor.travel(new Vec3(0,0,1));
            h.assertTrue(actor.getX()>before.x+.5,"restricted entity did not move from impulse");h.assertTrue(actor.getDeltaMovement().y<0,"restricted entity lost gravity");
            t.runtime.unbind("move");actor.setDeltaMovement(Vec3.ZERO);actor.moveRelative(1,new Vec3(0,0,1));h.assertTrue(actor.getDeltaMovement().z>.9,"movement did not recover independently of jump");
            actor.setDeltaMovement(Vec3.ZERO);actor.jumpFromGround();h.assertValueEqual(actor.getDeltaMovement(),Vec3.ZERO,"jump should remain denied");t.runtime.unbind("jump");actor.jumpFromGround();h.assertTrue(actor.getDeltaMovement().y>0,"jump failed after last denial removed");t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_movement_jumps")
    public void groundAndLiquidOverridesCannotBypassJumpRestrictions(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            for(var type:List.of(EntityTypes.ZOMBIE,EntityTypes.RABBIT,EntityTypes.SNIFFER,EntityTypes.SLIME,EntityTypes.MAGMA_CUBE,EntityTypes.BEE)){
                var actor=t.mob(type,2,2);t.runtime.bind(source("jump","jump_guard",actor));actor.setDeltaMovement(.15,-.1,.2);var before=actor.getDeltaMovement();actor.jumpFromGround();h.assertValueEqual(actor.getDeltaMovement(),before,"denied ground jump: "+type);
                var liquid=LivingEntity.class.getDeclaredMethod("jumpInLiquid",TagKey.class);liquid.setAccessible(true);
                for(TagKey<Fluid> fluid:List.of(FluidTags.WATER,FluidTags.LAVA)){liquid.invoke(actor,fluid);h.assertValueEqual(actor.getDeltaMovement(),before,"denied liquid jump: "+type);}
                t.runtime.unbind("jump");actor.setDeltaMovement(Vec3.ZERO);actor.jumpFromGround();if(type!=EntityTypes.SNIFFER)h.assertTrue(actor.getDeltaMovement().y>0,"ground jump not restored: "+type);
                actor.discard();
            }t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_movement_expiry",maxTicks=18)
    public void recipientExpiryCannotClearAnotherSourceAndKeepsCasterEvidence(GameTestHelper h){
        var t=harness(h);try{
            var actor=t.mob(EntityTypes.ZOMBIE,2,2);var caster=t.mob(EntityTypes.COW,4,2);var source=source("caster","movement_inputs",caster);t.runtime.bind(source);
            t.runtime.start(new RuleEngine.Signal("test:lock",new EffectEvent(source.holder(),actor.getUUID().toString(),source.origin(),Set.of(),Map.of())));h.assertValueEqual(mask(actor),3,"recipient received both restrictions");h.assertValueEqual(mask(caster),0,"caster restricted instead of recipient");
            var report=t.runtime.nativeMovementReport().stream().filter(r->r.holder().equals(actor.getUUID().toString())&&r.query().decision().get().action()==ActionGate.Kind.MOVEMENT_INPUT).findFirst().orElseThrow().query();
            h.assertValueEqual(report.decision().get().phase(),ActionGate.Phase.CONTINUE,"movement query phase");h.assertValueEqual(report.decision().get().denials().getFirst().origin(),source.origin(),"movement caster identity");
            t.runtime.bind(source("move","movement_guard",actor));
            h.runAfterDelay(8,()->{try(t){t.runtime.prepare();h.assertValueEqual(mask(actor),1,"expiry cleared a still active source or retained jump");t.runtime.unbind("move");h.assertValueEqual(mask(actor),0,"final removal retained restriction");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }

    @GameCase(environment="chorus_gametest:native_movement_player")
    public void serverFiltersHeldAndNewPlayerInputAndRuntimeCloseClearsOnlyItsState(GameTestHelper h){
        try(var t=harness(h)){
            var player=NativeMeleeGameTest.player(h);try{
                var all=new Input(true,false,true,false,true,true,true);player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(all));h.assertValueEqual(player.getLastClientInput(),all,"baseline input");guard(t,player);
                h.assertValueEqual(player.getLastClientInput(),Input.EMPTY,"existing held input not filtered");player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(all));h.assertValueEqual(player.getLastClientInput(),Input.EMPTY,"new packet bypassed restrictions");h.assertTrue(!player.isShiftKeyDown(),"server crouch bypassed filter");
                t.runtime.unbind("move");player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(all));h.assertTrue(player.getLastClientInput().forward()&&!player.getLastClientInput().jump(),"independent jump restriction");
                t.runtime.close();h.assertValueEqual(mask(player),0,"closed runtime left client flags");player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(all));h.assertValueEqual(player.getLastClientInput(),all,"closed runtime still filtered packets");
            }finally{player.discard();}
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_movement_failure")
    public void unobservedConditionsStopTheRuntimeWithoutAuthorizingMovementOrInventingADecision(GameTestHelper h){
        try(var t=harness(h)){
            var actor=t.mob(EntityTypes.ZOMBIE,2,2);boolean threw=false;try{t.runtime.bind(source("bad","movement_bad_query",actor));}catch(IllegalArgumentException expected){threw=true;}
            h.assertTrue(threw&&t.runtime.failure().isPresent(),"broken movement query was silently accepted");h.assertValueEqual(mask(actor),1,"failed query authorized movement");
            var report=t.runtime.nativeMovementReport().stream().filter(r->r.query().outcome()==MinecraftNativeActions.Outcome.QUERY_FAILED).findFirst().orElseThrow().query();h.assertTrue(report.decision().isEmpty()&&report.failure().isPresent(),"failure invented a normal denial");
            actor.setDeltaMovement(Vec3.ZERO);actor.moveRelative(1,new Vec3(0,0,1));h.assertValueEqual(actor.getDeltaMovement(),Vec3.ZERO,"stopped runtime lost committed restriction");t.runtime.close();actor.moveRelative(1,new Vec3(0,0,1));h.assertTrue(actor.getDeltaMovement().lengthSqr()>.9,"explicit close did not release restriction");
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_movement_ai",maxTicks=120)
    public void autonomousAiKeepsTargetAndMeleeAndResumesWalkingAfterRemoval(GameTestHelper h){
        var t=harness(h);try{
            for(int x=0;x<12;x++)for(int z=0;z<6;z++)h.setBlock(x,39,z,net.minecraft.world.level.block.Blocks.STONE);
            var actor=h.spawn(EntityTypes.ZOMBIE,2,40,2);t.mobs.add(actor);actor.setItemSlot(EquipmentSlot.HEAD,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));var target=t.mob(EntityTypes.IRON_GOLEM,9,2);actor.setTarget(target);guard(t,actor);var start=actor.position();
            h.runAfterDelay(25,()->{try{
                h.assertTrue(actor.position().subtract(start).horizontalDistanceSqr()<.01,"AI input accelerated restricted mob");h.assertTrue(actor.getTarget()==target&&!actor.isNoAi(),"movement restriction disabled unrelated AI");
                var nearby=t.mob(EntityTypes.COW,3,2);h.assertTrue(actor.doHurtTarget(t.level,nearby),"movement restriction blocked melee");nearby.discard();t.runtime.unbind("move");t.runtime.unbind("jump");
            }catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(85,()->{try(t){h.assertTrue(actor.position().subtract(start).horizontalDistanceSqr()>1,"autonomous navigation failed to resume");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
