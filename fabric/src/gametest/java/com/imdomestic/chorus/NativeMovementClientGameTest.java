package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.test.NativeMovementGameTest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Chorus control packets, real held keyboard input, accepted server positions and impulse packets. */
public final class NativeMovementClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context){
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();var start=new AtomicReference<Vec3>();var mark=new AtomicReference<Vec3>();var runtime=new AtomicReference<MinecraftEffectRuntime>();var world=new AtomicReference<MinecraftWorldActions>();var remote=new AtomicReference<net.minecraft.world.entity.animal.cow.Cow>();
            try{
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();var level=player.level();var center=player.blockPosition().above(5);
                    for(int x=-4;x<=12;x++)for(int z=-5;z<=60;z++){
                        level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                        for(int y=0;y<=4;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());
                    }
                    start.set(new Vec3(center.getX()+.5,center.getY(),center.getZ()+.5));player.setGameMode(GameType.SURVIVAL);player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);
                    String holder=player.getUUID().toString();world.set(new MinecraftWorldActions(level,ref->ref.equals(holder)?player:null,_->level.damageSources().generic(),(_,_) -> true,_ -> {}));
                    runtime.set(MinecraftEffectRuntime.install(level,NativeMovementGameTest.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),world.get(),MinecraftEffectRuntime::nativeSource));
                    runtime.get().bind(NativeMovementGameTest.source("move","movement_guard",player));runtime.get().bind(NativeMovementGameTest.source("jump","jump_guard",player));
                    var cow=net.minecraft.world.entity.EntityTypes.COW.create(level,net.minecraft.world.entity.EntitySpawnReason.COMMAND);cow.setPos(start.get().add(3,0,0));cow.setNoAi(true);cow.setNoGravity(true);level.addFreshEntity(cow);remote.set(cow);
                    runtime.get().bind(NativeMovementGameTest.source("cow-move","movement_guard",cow));runtime.get().bind(NativeMovementGameTest.source("cow-jump","jump_guard",cow));
                });
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==3&&client.player.onGround()&&client.player.position().distanceTo(start.get())<.1);
                context.waitFor(client->client.level.getEntity(remote.get().getId()) instanceof net.minecraft.world.entity.LivingEntity e&&NativeMovementGameTest.mask(e)==3);
                context.runOnClient(client->{
                    var p=client.player;
                    com.imdomestic.chorus.client.MovementInputClient.accept(new com.imdomestic.chorus.network.MovementInputPayload("test:other_dimension",p.getId(),p.getUUID(),0));
                    com.imdomestic.chorus.client.MovementInputClient.accept(new com.imdomestic.chorus.network.MovementInputPayload(client.level.dimension().identifier().toString(),p.getId(),UUID.randomUUID(),0));
                    if(NativeMovementGameTest.mask(p)!=3)throw new AssertionError("Stale identity cleared current restriction");
                });
                context.getInput().holdKey(o->o.keyUp);context.getInput().holdKey(o->o.keyJump);context.getInput().holdKey(o->o.keySprint);context.waitTicks(15);connection.waitForServerboundPackets();
                context.runOnClient(client->{
                    if(!client.player.input.keyPresses.equals(Input.EMPTY)||client.player.input.getMoveVector().lengthSquared()!=0)throw new AssertionError("Held keys survived synchronized restrictions");
                    if(client.player.position().distanceTo(start.get())>.1)throw new AssertionError("Restricted client accelerated or jumped");
                });
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();if(player.position().distanceTo(start.get())>.1)throw new AssertionError("Server accepted unexpected movement during honest held input");
                    var command=new Impulse.Command(player.getUUID().toString(),Optional.of(new WorldDirection(player.level().dimension().identifier().toString(),1,0,0)),10,Impulse.Scale.ONE,NativeMovementGameTest.source("external","movement_guard",player).origin(),Set.of());
                    var result=(Impulse.Receipt)world.get().apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1,0,0),command));if(result.outcome()!=Impulse.Outcome.APPLIED)throw new AssertionError("Restricted player's external impulse rejected");
                });
                context.waitFor(client->client.player.getX()>start.get().x+.5);context.waitTicks(12);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();if(player.getX()<=start.get().x+.5)throw new AssertionError("Server did not accept external impulse movement");
                    mark.set(player.position());runtime.get().unbind("move");
                });
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==2&&client.player.getZ()>mark.get().z+1);
                context.runOnClient(client->{if(!client.player.onGround()||client.player.input.keyPresses.jump())throw new AssertionError("Restored walking bypassed remaining jump restriction");});
                game.getServer().runOnServer(server->runtime.get().unbind("jump"));
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==0&&client.player.getY()>start.get().y+.1);
                context.getInput().releaseKey(o->o.keyUp);context.getInput().releaseKey(o->o.keyJump);context.getInput().releaseKey(o->o.keySprint);context.waitFor(client->client.player.onGround());
                game.getServer().runOnServer(server->{var player=connection.getServerPlayer();runtime.get().bind(NativeMovementGameTest.source("move","movement_guard",player));runtime.get().bind(NativeMovementGameTest.source("jump","jump_guard",player));});
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==3);
                game.getServer().runOnServer(server->{if(runtime.get().failure().isPresent())throw new AssertionError("Movement runtime failed: "+runtime.get().failure());runtime.get().close();mark.set(connection.getServerPlayer().position());});
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==0&&client.level.getEntity(remote.get().getId()) instanceof net.minecraft.world.entity.LivingEntity e&&NativeMovementGameTest.mask(e)==0);context.getInput().holdKey(o->o.keyUp);context.waitFor(client->client.player.getZ()>mark.get().z+1);
            }finally{
                context.getInput().releaseKey(o->o.keyUp);context.getInput().releaseKey(o->o.keyJump);context.getInput().releaseKey(o->o.keySprint);
                game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();if(remote.get()!=null)remote.get().discard();});
            }
        }
    }
}
