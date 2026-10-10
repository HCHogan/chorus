package com.imdomestic.chorus;

import com.imdomestic.chorus.client.HorizontalSpeedClient;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.network.HorizontalSpeedPayload;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.test.HorizontalSpeedGameTest;
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

/** Real airborne input, radial speed, server budget, tracking identities, cap-only teleports and release. */
public final class HorizontalSpeedClientGameTest implements FabricClientGameTest {
    record Mark(Vec3 position,long tick) {}
    static double horizontal(Vec3 vector){return Math.hypot(vector.x,vector.z);}
    static boolean cap(Entity actor,double speed){return MinecraftHorizontalSpeed.speed(actor).equals(OptionalDouble.of(speed));}
    @Override public void runTest(ClientGameTestContext context){
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();var runtime=new AtomicReference<MinecraftEffectRuntime>();var remote=new AtomicReference<Mob>();var target=new AtomicReference<Vec3>();var serverMark=new AtomicReference<Mark>();var clientMark=new AtomicReference<Mark>();
            try{
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();var level=player.level();var center=player.blockPosition().above(5);
                    for(int x=-10;x<=10;x++)for(int z=-10;z<=20;z++){level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());for(int y=0;y<=8;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());}
                    player.setGameMode(GameType.SURVIVAL);player.connection.teleport(center.getX()+.5,center.getY()+3,center.getZ()+.5,0,0);
                    var world=new MinecraftWorldActions(level,id->{var e=level.getEntity(UUID.fromString(id));return e instanceof LivingEntity l?l:null;},_->level.damageSources().generic(),(_,_) -> true,_ -> {});
                    runtime.set(MinecraftEffectRuntime.install(level,HorizontalSpeedGameTest.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),world::apply,MinecraftEffectRuntime::nativeSource));
                    runtime.get().bind(HorizontalSpeedGameTest.source("cap",player,2));runtime.get().bind(NativeMotionGameTest.source("height","vertical",player));
                    var mob=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);mob.setPos(player.position().add(4,0,0));mob.setNoAi(true);mob.setNoGravity(true);level.addFreshEntity(mob);remote.set(mob);runtime.get().bind(HorizontalSpeedGameTest.source("remote",mob,0));
                });
                context.waitFor(client->cap(client.player,2)&&NativeMovementGameTest.mask(client.player)==8);
                context.waitFor(client->client.level.getEntity(remote.get().getId()) instanceof LivingEntity actor&&cap(actor,0));
                context.runOnClient(client->{
                    var actor=client.player;String dimension=actor.level().dimension().identifier().toString();
                    HorizontalSpeedClient.accept(new HorizontalSpeedPayload(dimension,actor.getId(),UUID.randomUUID(),OptionalDouble.of(0)));
                    HorizontalSpeedClient.accept(new HorizontalSpeedPayload("test:other_world",actor.getId(),actor.getUUID(),OptionalDouble.of(0)));
                    if(!cap(actor,2))throw new AssertionError("Stale identity or dimension payload changed local prediction");
                    clientMark.set(new Mark(actor.position(),actor.level().getGameTime()));
                });
                game.getServer().runOnServer(server->{var actor=connection.getServerPlayer();serverMark.set(new Mark(actor.position(),actor.level().getGameTime()));});
                context.getInput().holdKey(o->o.keyUp);context.getInput().holdKey(o->o.keyLeft);context.getInput().holdKey(o->o.keySprint);context.getInput().holdKey(o->o.keyJump);
                context.waitTicks(24);connection.waitForServerboundPackets();
                context.runOnClient(client->{
                    var actor=client.player;var mark=clientMark.get();double moved=horizontal(actor.position().subtract(mark.position()));long ticks=actor.level().getGameTime()-mark.tick();
                    if(moved<=.1||moved>.1*(ticks+1)+1e-5||horizontal(actor.getDeltaMovement())>.1+1e-7||Math.abs(actor.getY()-mark.position().y)>1e-5)throw new AssertionError("Airborne diagonal/sprint input escaped client speed or height limit: "+moved+" / "+ticks);
                    if(!actor.input.keyPresses.forward())throw new AssertionError("Speed limit suppressed movement input");
                });
                game.getServer().runOnServer(server->{
                    var actor=connection.getServerPlayer();var mark=serverMark.get();double moved=horizontal(actor.position().subtract(mark.position()));long ticks=actor.level().getGameTime()-mark.tick();
                    if(moved<=.1||moved>.1*(ticks+1)+1e-5||Math.abs(actor.getY()-mark.position().y)>1e-5)throw new AssertionError("Server movement budget failed: "+moved+" / "+ticks);
                    runtime.get().bind(HorizontalSpeedGameTest.source("tight",actor,.5));target.set(remote.get().position().add(3,0,0));remote.get().teleportTo(target.get().x,target.get().y,target.get().z);
                });
                context.waitFor(client->cap(client.player,.5));context.waitTicks(8);
                context.runOnClient(client->{if(horizontal(client.player.getDeltaMovement())>.025+1e-7)throw new AssertionError("Tighter ceiling did not clamp velocity");});
                context.waitFor(client->client.level.getEntity(remote.get().getId()).position().distanceTo(target.get())<1e-4);
                game.getServer().runOnServer(server->runtime.get().unbind("tight"));context.waitFor(client->cap(client.player,2));
                game.getServer().runOnServer(server->{runtime.get().bind(HorizontalSpeedGameTest.source("zero",connection.getServerPlayer(),0));});
                context.waitFor(client->cap(client.player,0));context.waitTicks(8);context.runOnClient(client->{if(horizontal(client.player.getDeltaMovement())!=0)throw new AssertionError("Zero was treated as no ceiling");});
                context.getInput().releaseKey(o->o.keyUp);context.getInput().releaseKey(o->o.keyLeft);context.getInput().releaseKey(o->o.keySprint);context.getInput().releaseKey(o->o.keyJump);
                game.getServer().runOnServer(server->{var actor=connection.getServerPlayer();actor.setNoGravity(true);runtime.get().unbind("height");});
                context.waitFor(client->client.player.isNoGravity()&&NativeMovementGameTest.mask(client.player)==0);
                game.getServer().runOnServer(server->{var actor=connection.getServerPlayer();target.set(actor.position().add(3,2,1));actor.teleportRelative(3,2,1);});
                context.waitFor(client->client.player.position().distanceTo(target.get())<1e-5);
                context.runOnClient(client->{if(!cap(client.player,0))throw new AssertionError("Cap-only teleport removed restriction");});
                game.getServer().runOnServer(server->{if(runtime.get().failure().isPresent())throw new AssertionError(runtime.get().failure());runtime.get().close();});
                context.waitFor(client->MinecraftHorizontalSpeed.speed(client.player).isEmpty()&&MinecraftHorizontalSpeed.speed(client.level.getEntity(remote.get().getId())).isEmpty());
            }finally{
                context.getInput().releaseKey(o->o.keyUp);context.getInput().releaseKey(o->o.keyLeft);context.getInput().releaseKey(o->o.keySprint);context.getInput().releaseKey(o->o.keyJump);
                game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();if(remote.get()!=null)remote.get().discard();});
            }
        }
    }
}
