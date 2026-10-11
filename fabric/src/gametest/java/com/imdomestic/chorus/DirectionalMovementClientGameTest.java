package com.imdomestic.chorus;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.registry.ChorusAttributes;
import com.imdomestic.chorus.test.NativeMovementGameTest;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real keyboard travel, native attribute delivery and accepted integrated-server positions. */
public final class DirectionalMovementClientGameTest implements FabricClientGameTest {
    private static boolean near(double actual,double expected) { return Math.abs(actual-expected)<1e-5; }
    @Override public void runTest(ClientGameTestContext context) {
        try(var game=context.worldBuilder().create()) {
            var connection=game.getConnection();connection.waitForChunksRender();
            var runtime=new AtomicReference<MinecraftEffectRuntime>();var start=new AtomicReference<Vec3>();
            var samples=new double[4];
            try {
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();var level=player.level();var center=player.blockPosition().above(5);
                    for(int x=-32;x<=12;x++)for(int z=-4;z<=60;z++) {
                        level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                        for(int y=0;y<=3;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());
                    }
                    start.set(new Vec3(center.getX()+.5,center.getY(),center.getZ()+.5));
                    player.setGameMode(GameType.SURVIVAL);player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);
                    try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/directional_movement.json")),StandardCharsets.UTF_8)) {
                        var program=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,JsonParser.parseReader(reader)).getOrThrow();
                        runtime.set(MinecraftEffectRuntime.install(level,program,EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{throw new AssertionError("Unexpected world action");},MinecraftEffectRuntime::nativeSource));
                    } catch(Exception error) {throw new RuntimeException(error);}
                });
                context.waitFor(client->client.player.onGround()&&client.player.position().distanceTo(start.get())<.1);
                for(int phase=0;phase<4;phase++) {
                    boolean boosted=(phase&1)!=0;boolean strafe=phase>=2;int index=phase;
                    game.getServer().runOnServer(server->{
                        if(boosted)runtime.get().bind(NativeMovementGameTest.source("bonus","strong",connection.getServerPlayer()));
                        else runtime.get().unbind("bonus");
                    });
                    context.waitFor(client->near(client.player.getAttributeValue(ChorusAttributes.forwardGroundSpeed()),boosted?1.5:1));
                    if(strafe)context.getInput().holdKey(options->options.keyRight);else context.getInput().holdKey(options->options.keyUp);
                    context.waitTicks(18);
                    context.runOnClient(client->{
                        if(!client.player.onGround()||client.player.isSprinting())throw new AssertionError("Expected unsprinted ground keyboard travel");
                        samples[index]=client.player.getDeltaMovement().horizontalDistance();
                        if(samples[index]<.01)throw new AssertionError("Held input did not move the client");
                    });
                    if(strafe)context.getInput().releaseKey(options->options.keyRight);else context.getInput().releaseKey(options->options.keyUp);
                    context.waitTicks(12);connection.waitForServerboundPackets();
                }
                if(Math.abs(samples[1]/samples[0]-1.5)>.02)throw new AssertionError("Forward keyboard speed ratio: "+Arrays.toString(samples));
                if(Math.abs(samples[3]/samples[2]-1)>.02)throw new AssertionError("Strafe keyboard speed changed: "+Arrays.toString(samples));
                var accepted=new AtomicReference<Vec3>();
                game.getServer().runOnServer(server->{
                    if(runtime.get().failure().isPresent())throw new AssertionError("Movement runtime failed: "+runtime.get().failure());
                    accepted.set(connection.getServerPlayer().position());
                    if(accepted.get().distanceTo(start.get())<3)throw new AssertionError("Server did not accept keyboard movement");
                    runtime.get().close();
                });
                context.waitFor(client->near(client.player.getAttributeValue(ChorusAttributes.forwardGroundSpeed()),1));
                context.runOnClient(client->{
                    if(client.player.position().distanceTo(accepted.get())>.1)throw new AssertionError("Client and server positions differ after walking");
                    if(client.player.getAttribute(ChorusAttributes.forwardGroundSpeed()).getModifiers().stream().anyMatch(modifier->modifier.id().getNamespace().equals("chorus")))throw new AssertionError("Client retained closed projection");
                });
            } finally {
                context.getInput().releaseKey(options->options.keyUp);context.getInput().releaseKey(options->options.keyRight);
                game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();});
            }
        }
    }
}
