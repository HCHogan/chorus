package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.test.NativeMotionGameTest;
import com.imdomestic.chorus.test.NativeMovementGameTest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real airborne player, held controls, relative teleports, free-axis motion, tracking and release to gravity. */
public final class NativeMotionClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context){
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();var start=new AtomicReference<Vec3>();var mark=new AtomicReference<Vec3>();var runtime=new AtomicReference<MinecraftEffectRuntime>();var world=new AtomicReference<MinecraftWorldActions>();var remote=new AtomicReference<LivingEntity>();
            try{
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();var level=player.level();var center=player.blockPosition().above(5);
                    for(int x=-5;x<=12;x++)for(int z=-5;z<=120;z++){
                        level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                        for(int y=0;y<=8;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());
                    }
                    start.set(new Vec3(center.getX()+.5,center.getY()+3,center.getZ()+.5));player.setGameMode(GameType.SURVIVAL);player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);
                    String holder=player.getUUID().toString();world.set(new MinecraftWorldActions(level,ref->ref.equals(holder)?player:null,_->level.damageSources().generic(),(_,_) -> true,_ -> {}));
                    runtime.set(MinecraftEffectRuntime.install(level,NativeMotionGameTest.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),world.get(),MinecraftEffectRuntime::nativeSource));
                    for(String axis:List.of("horizontal","vertical"))runtime.get().bind(NativeMotionGameTest.source(axis,axis,player));
                    var cow=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);cow.setPos(start.get().add(3,0,0));cow.setNoAi(true);level.addFreshEntity(cow);remote.set(cow);runtime.get().bind(NativeMotionGameTest.source("cow","motion",cow));
                });
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==12&&client.player.position().distanceTo(start.get())<1e-5);
                context.waitFor(client->client.level.getEntity(remote.get().getId()) instanceof LivingEntity e&&NativeMovementGameTest.mask(e)==12&&e.position().distanceTo(start.get().add(3,0,0))<1e-5);
                context.getInput().holdKey(o->o.keyUp);context.getInput().holdKey(o->o.keyJump);context.getInput().holdKey(o->o.keySprint);context.waitTicks(20);connection.waitForServerboundPackets();
                context.runOnClient(client->{
                    if(client.player.position().distanceTo(start.get())>1e-5||client.player.getDeltaMovement().lengthSqr()>1e-10)throw new AssertionError("Held input or gravity escaped physical constraints");
                    if(!client.player.input.keyPresses.forward())throw new AssertionError("Axis constraint was implemented by suppressing input instead of motion");
                });
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();if(player.position().distanceTo(start.get())>1e-5)throw new AssertionError("Server anchor drifted");
                    var command=new Impulse.Command(player.getUUID().toString(),Optional.of(new WorldDirection(player.level().dimension().identifier().toString(),1,1,0)),10,Impulse.Scale.ONE,NativeMotionGameTest.source("external","motion",player).origin(),Set.of());
                    var result=(Impulse.Receipt)world.get().apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1,0,0),command));if(result.outcome()!=Impulse.Outcome.UNCHANGED)throw new AssertionError("Fully blocked impulse claimed success");
                    player.teleportRelative(3,2,1);mark.set(start.get().add(3,2,1));if(player.position().distanceTo(mark.get())>1e-5)throw new AssertionError("Server relative teleport failed");
                });
                context.waitFor(client->client.player.position().distanceTo(mark.get())<1e-5);context.waitTicks(8);
                context.runOnClient(client->{if(client.player.position().distanceTo(mark.get())>1e-5||NativeMovementGameTest.mask(client.player)!=12)throw new AssertionError("Relative teleport applied twice or cleared constraint");});
                game.getServer().runOnServer(server->runtime.get().unbind("horizontal"));
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==8&&client.player.getZ()>mark.get().z+1);context.waitTicks(100);connection.waitForServerboundPackets();
                context.runOnClient(client->{if(Math.abs(client.player.getY()-mark.get().y)>1e-5)throw new AssertionError("Height hold drifted during horizontal travel");});
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();if(player.getZ()<=mark.get().z+1||Math.abs(player.getY()-mark.get().y)>1e-5)throw new AssertionError("Server rejected free-axis travel or accepted locked height drift");
                    if(runtime.get().failure().isPresent())throw new AssertionError("Motion runtime failed: "+runtime.get().failure());runtime.get().close();
                });
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==0&&client.player.getY()<mark.get().y-.2);
                context.waitFor(client->client.level.getEntity(remote.get().getId()) instanceof LivingEntity e&&NativeMovementGameTest.mask(e)==0);
            }finally{
                context.getInput().releaseKey(o->o.keyUp);context.getInput().releaseKey(o->o.keyJump);context.getInput().releaseKey(o->o.keySprint);
                game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();if(remote.get()!=null)remote.get().discard();});
            }
        }
    }
}
