package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.test.DisplacementGameTest;
import com.imdomestic.chorus.test.NativeMovementGameTest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real client receives every native position correction, holds the new anchor, and falls after release. */
public final class DisplacementClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context){
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();var runtime=new AtomicReference<MinecraftEffectRuntime>();var source=new AtomicReference<EffectSource>();var center=new AtomicReference<BlockPos>();var start=new AtomicReference<Vec3>();var expected=new AtomicReference<Vec3>();var receipts=new ArrayList<Displacement.Receipt>();
            try{
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();var level=player.level();center.set(player.blockPosition().above(5));
                    for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++){
                        level.setBlockAndUpdate(center.get().offset(x,-1,z),Blocks.STONE.defaultBlockState());
                        for(int y=0;y<=7;y++)level.setBlockAndUpdate(center.get().offset(x,y,z),Blocks.AIR.defaultBlockState());
                    }
                    start.set(new Vec3(center.get().getX()+.5,center.get().getY()+2,center.get().getZ()+.5));expected.set(start.get().add(0,1.05,0));player.setGameMode(GameType.SURVIVAL);player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);
                    var world=new MinecraftWorldActions(level,id->id.equals(player.getUUID().toString())?player:null,_->level.damageSources().generic(),(_,_) -> true,_ -> {});
                    runtime.set(MinecraftEffectRuntime.install(level,DisplacementGameTest.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{var result=world.apply(request);if(result instanceof Displacement.Receipt receipt)receipts.add(receipt);return result;},MinecraftEffectRuntime::nativeSource));
                    source.set(DisplacementGameTest.source(player));runtime.get().bind(source.get());DisplacementGameTest.event(runtime.get(),source.get(),player,"test:lift");
                });
                context.getInput().holdKey(o->o.keyUp);context.getInput().holdKey(o->o.keyJump);
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==12&&client.player.position().distanceTo(expected.get())<1e-5);context.waitTicks(12);connection.waitForServerboundPackets();
                context.runOnClient(client->{if(client.player.position().distanceTo(expected.get())>1e-5)throw new AssertionError("Incremental player teleports reverted to an old anchor");});
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();if(receipts.size()!=5||receipts.stream().anyMatch(Displacement.Receipt::clipped)||player.position().distanceTo(expected.get())>1e-5)throw new AssertionError("Staged lift did not settle in five native moves");
                    if(Math.abs(receipts.getLast().command().distance()-.05)>1e-6)throw new AssertionError("Final short step missing");DisplacementGameTest.event(runtime.get(),source.get(),player,"test:release");
                });
                context.getInput().releaseKey(o->o.keyUp);context.getInput().releaseKey(o->o.keyJump);
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==0&&client.player.getY()<expected.get().y-.2);
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)player.level().setBlockAndUpdate(center.get().offset(x,3,z),Blocks.STONE.defaultBlockState());
                    start.set(new Vec3(center.get().getX()+.5,center.get().getY()+.35,center.get().getZ()+.5));player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);player.setDeltaMovement(Vec3.ZERO);
                    expected.set(start.get().add(0,center.get().getY()+3-player.getBoundingBox().maxY,0));receipts.clear();DisplacementGameTest.event(runtime.get(),source.get(),player,"test:lift");
                });
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==12&&client.player.position().distanceTo(expected.get())<1e-5);context.waitTicks(12);connection.waitForServerboundPackets();
                context.runOnClient(client->{if(client.player.position().distanceTo(expected.get())>1e-5||client.player.getBoundingBox().maxY>center.get().getY()+3+1e-5)throw new AssertionError("Client penetrated ceiling or drifted from clipped anchor");});
                game.getServer().runOnServer(server->{
                    if(receipts.size()!=4||!receipts.getLast().clipped())throw new AssertionError("Ceiling did not cancel after three full steps and a partial step: "+receipts);
                    if(connection.getServerPlayer().position().distanceTo(expected.get())>1e-5)throw new AssertionError("Server/client ceiling height differs");
                });
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==0&&client.player.getY()<expected.get().y-.2);
                game.getServer().runOnServer(server->{if(receipts.size()!=4||runtime.get().failure().isPresent())throw new AssertionError("Expired lift retried a stopped timer or failed");});
            }finally{
                context.getInput().releaseKey(o->o.keyUp);context.getInput().releaseKey(o->o.keyJump);game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();});
            }
        }
    }
}
