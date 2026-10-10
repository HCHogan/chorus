package com.imdomestic.chorus;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;

/** The normal native attribute packet must apply, update and remove Chorus contributions on a real client. */
public final class NativeAttributeClientGameTest implements FabricClientGameTest {
    private static boolean near(double actual,double expected){return Math.abs(actual-expected)<1e-6;}
    @Override public void runTest(ClientGameTestContext context){
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();
            var runtime=new AtomicReference<MinecraftEffectRuntime>();var baseline=new AtomicReference<double[]>();
            try{
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();player.setGameMode(GameType.SURVIVAL);
                    baseline.set(new double[]{player.getAttributeValue(Attributes.MOVEMENT_SPEED),player.getAttributeValue(Attributes.GRAVITY)});
                    String holder=player.getUUID().toString();
                    try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/native_attributes.json")),StandardCharsets.UTF_8)){
                        var data=JsonParser.parseReader(reader).getAsJsonObject();
                        // Keep the synthetic gravity contribution active independently of packet scheduling.
                        var slow=data.getAsJsonArray("bundles").asList().stream().map(e->e.getAsJsonObject()).filter(b->b.get("id").getAsString().equals("test:slow")).findFirst().orElseThrow().deepCopy();
                        slow.addProperty("id","test:flight");slow.remove("scope");data.getAsJsonArray("bundles").add(slow);
                        var program=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow();
                        runtime.set(MinecraftEffectRuntime.install(player.level(),program,EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{throw new AssertionError("Unexpected world action: "+request);},MinecraftEffectRuntime::nativeSource));
                        for(String name:List.of("fast","flight"))runtime.get().bind(new EffectSource(name,"test:"+name,holder,new BuffInstance.Origin(holder,name,"",""),Set.of()));
                    }catch(Exception failure){throw new RuntimeException(failure);}
                });
                context.waitFor(client->near(client.player.getAttributeValue(Attributes.MOVEMENT_SPEED),baseline.get()[0]*1.25)&&near(client.player.getAttributeValue(Attributes.GRAVITY),baseline.get()[1]*.5));
                game.getServer().runOnServer(server->runtime.get().unbind("flight"));
                context.waitFor(client->near(client.player.getAttributeValue(Attributes.MOVEMENT_SPEED),baseline.get()[0]*1.5)&&near(client.player.getAttributeValue(Attributes.GRAVITY),baseline.get()[1]));
                game.getServer().runOnServer(server->{
                    if(runtime.get().failure().isPresent())throw new AssertionError("Attribute runtime failed: "+runtime.get().failure());
                    runtime.get().close();
                });
                context.waitFor(client->near(client.player.getAttributeValue(Attributes.MOVEMENT_SPEED),baseline.get()[0])&&near(client.player.getAttributeValue(Attributes.GRAVITY),baseline.get()[1]));
                context.runOnClient(client->{
                    for(var attribute:List.of(Attributes.MOVEMENT_SPEED,Attributes.GRAVITY))
                        if(client.player.getAttribute(attribute).getModifiers().stream().anyMatch(m->m.id().getNamespace().equals("chorus")&&m.id().getPath().startsWith("projection/")))throw new AssertionError("Client retained a closed runtime's modifier");
                });
            }finally{game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();});}
        }
    }
}
