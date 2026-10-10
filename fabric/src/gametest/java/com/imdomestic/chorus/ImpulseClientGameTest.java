package com.imdomestic.chorus;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real client consumes the vanilla velocity packet, moves, collides and reports its position to the server. */
public final class ImpulseClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();
            var start=new AtomicReference<Vec3>();var wall=new AtomicReference<Double>();var receipt=new AtomicReference<Impulse.Receipt>();
            game.getServer().runOnServer(server->{
                var player=connection.getServerPlayer();var level=player.level();var center=player.blockPosition().above(5);
                for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++){
                    level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                    for(int y=0;y<=3;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());
                }
                for(int x=-8;x<=8;x++)for(int y=0;y<3;y++)level.setBlockAndUpdate(center.offset(x,y,2),Blocks.STONE.defaultBlockState());
                start.set(new Vec3(center.getX()+.5,center.getY(),center.getZ()+.5));wall.set((double)center.getZ()+2);
                player.setGameMode(GameType.SURVIVAL);player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);
            });
            connection.waitForClientboundPackets();context.waitFor(client->client.player.position().distanceTo(start.get())<.05);
            context.waitTicks(3);
            game.getServer().runOnServer(server->{
                var player=connection.getServerPlayer();var level=player.level();String holder=player.getUUID().toString();
                try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/impulse.json")),StandardCharsets.UTF_8)){
                    var program=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,JsonParser.parseReader(reader)).getOrThrow();
                    var world=new MinecraftWorldActions(level,id->id.equals(holder)?player:null,_->level.damageSources().generic(),(_,_) -> true,_ -> {});
                    var runtime=MinecraftEffectRuntime.install(level,program,EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{
                        var result=world.apply(request);if(result instanceof Impulse.Receipt r){receipt.set(r);if(r.outcome()==Impulse.Outcome.APPLIED&&!player.isInPostImpulseGraceTime())throw new AssertionError("Native movement grace was not applied");}return result;
                    },MinecraftEffectRuntime::nativeSource);
                    runtime.abilities(new AbilityChange(holder,AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:movement","test:dash"))));
                    player.setYRot(0);player.setXRot(0);
                    server.getCommands().getDispatcher().execute("chorus ability use test:movement",player.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS));
                }catch(Exception failure){throw new RuntimeException(failure);}
            });
            context.waitFor(client->client.player.getZ()>start.get().z+.3);context.waitTicks(15);connection.waitForServerboundPackets();
            var clientPosition=new AtomicReference<Vec3>();context.runOnClient(client->{
                if(client.player.getBoundingBox().maxZ>wall.get()+1e-4)throw new AssertionError("Impulse bypassed client collision");
                clientPosition.set(client.player.position());
            });
            game.getServer().runOnServer(server->{
                var player=connection.getServerPlayer();var runtime=MinecraftEffectRuntime.installed(player.level()).orElseThrow();
                try{
                    if(receipt.get()==null||receipt.get().outcome()!=Impulse.Outcome.APPLIED)throw new AssertionError("No successful impulse receipt");
                    if(runtime.failure().isPresent())throw new AssertionError("Movement runtime failed: "+runtime.failure());
                    if(player.getZ()<=start.get().z+.3||player.position().distanceTo(clientPosition.get())>.25)throw new AssertionError("Client movement was not accepted by the server");
                    if(player.getBoundingBox().maxZ>wall.get()+1e-4)throw new AssertionError("Server position bypassed wall");
                }finally{runtime.close();}
            });
        }
    }
}
