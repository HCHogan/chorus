package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.test.AmplifiedGameTest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real input, server sprint observation, timer activation, native attribute packets, and physical movement. */
public final class AmplifiedClientGameTest implements FabricClientGameTest {
    private static boolean projected(AttributeInstance attribute,double amount){return attribute.getModifiers().stream().anyMatch(m->m.id().getNamespace().equals("chorus")&&m.id().getPath().startsWith("projection/")&&Math.abs(m.amount()-amount)<1e-8);}
    private static boolean cleared(AttributeInstance attribute){return attribute.getModifiers().stream().noneMatch(m->m.id().getNamespace().equals("chorus")&&m.id().getPath().startsWith("projection/"));}
    @Override public void runTest(ClientGameTestContext context){
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();var start=new AtomicReference<Vec3>();var runtime=new AtomicReference<MinecraftEffectRuntime>();
            try{
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();var level=player.level();var center=player.blockPosition().above(5);
                    for(int x=-3;x<=3;x++)for(int z=-5;z<=120;z++){
                        level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                        for(int y=0;y<=3;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());
                    }
                    start.set(new Vec3(center.getX()+.5,center.getY(),center.getZ()+.5));
                    player.setGameMode(GameType.SURVIVAL);player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);
                    String holder=player.getUUID().toString();var world=new MinecraftWorldActions(level,ref->ref.equals(holder)?player:null,_->level.damageSources().generic(),(_,_) -> true,_ -> {});
                    runtime.set(MinecraftEffectRuntime.install(level,AmplifiedGameTest.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),world,MinecraftEffectRuntime::nativeSource));
                    runtime.get().bind(AmplifiedGameTest.source("inputs","test:amplified_inputs",holder));runtime.get().bind(AmplifiedGameTest.calibration(holder));
                });
                connection.waitForClientboundPackets();context.waitFor(client->client.player.position().distanceTo(start.get())<.1);context.waitTicks(3);
                context.getInput().holdKey(options->options.keyUp);context.getInput().holdKey(options->options.keySprint);
                context.waitFor(client->client.player.isSprinting());connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();if(!player.isSprinting())throw new AssertionError("Client sprint input not observed by server");
                    runtime.get().start(AmplifiedGameTest.grant(player.getUUID().toString(),player.getUUID().toString(),4));
                });
                context.waitFor(client->projected(client.player.getAttribute(Attributes.MOVEMENT_SPEED),.5)&&projected(client.player.getAttribute(Attributes.JUMP_STRENGTH),.1));
                context.waitTicks(40);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();String holder=player.getUUID().toString();
                    if(AmplifiedGameTest.count(runtime.get(),AmplifiedGameTest.A,holder)!=0||AmplifiedGameTest.count(runtime.get(),AmplifiedGameTest.S,holder)!=1)throw new AssertionError("Speed Booster did not outlive Amplified during actual sprint");
                    if(player.getZ()<start.get().z+10)throw new AssertionError("Accepted server position did not advance with the client");
                });
                context.getInput().releaseKey(options->options.keyUp);context.getInput().releaseKey(options->options.keySprint);
                context.waitFor(client->cleared(client.player.getAttribute(Attributes.MOVEMENT_SPEED))&&cleared(client.player.getAttribute(Attributes.JUMP_STRENGTH)));
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();if(AmplifiedGameTest.count(runtime.get(),AmplifiedGameTest.S,player.getUUID().toString())!=0)throw new AssertionError("Stopped sprint retained Speed Booster");
                    if(runtime.get().failure().isPresent())throw new AssertionError("Amplified runtime failed: "+runtime.get().failure());
                });
            }finally{
                context.getInput().releaseKey(options->options.keyUp);context.getInput().releaseKey(options->options.keySprint);
                game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();});
            }
        }
    }
}
