package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.test.NativeMovementGameTest;
import com.imdomestic.chorus.test.SlowGameTest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Actual ground input and local/tracked attribute packets, followed by Slow -> Freeze -> thaw. */
public final class SlowClientGameTest implements FabricClientGameTest {
    private static boolean projected(AttributeInstance a,double amount){return a.getModifiers().stream().anyMatch(m->m.id().getNamespace().equals("chorus")&&m.id().getPath().startsWith("projection/")&&Math.abs(m.amount()-amount)<1e-8);}
    private static boolean cleared(AttributeInstance a){return a.getModifiers().stream().noneMatch(m->m.id().getNamespace().equals("chorus")&&m.id().getPath().startsWith("projection/"));}
    @Override public void runTest(ClientGameTestContext context){
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();var runtime=new AtomicReference<MinecraftEffectRuntime>();var source=new AtomicReference<EffectSource>();var caster=new AtomicReference<Mob>();var combatant=new AtomicReference<Mob>();var start=new AtomicReference<Vec3>();var sample=new AtomicReference<Vec3>();var baseline=new AtomicReference<Double>();
            try{
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();var level=player.level();var center=player.blockPosition().above(5);
                    for(int x=-5;x<=7;x++)for(int z=-5;z<=120;z++){
                        level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());for(int y=0;y<5;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());
                    }
                    start.set(new Vec3(center.getX()+.5,center.getY(),center.getZ()+.5));player.setGameMode(GameType.SURVIVAL);player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);
                    var owner=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);owner.setPos(start.get().add(-3,0,0));owner.setNoAi(true);level.addFreshEntity(owner);caster.set(owner);
                    var npc=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);npc.setPos(start.get().add(4,0,0));npc.removeFreeWill();npc.addTag("chorus_d2:elite");level.addFreshEntity(npc);combatant.set(npc);
                    var world=new MinecraftWorldActions(level,id->{var e=level.getEntity(UUID.fromString(id));return e instanceof LivingEntity living?living:null;},_->level.damageSources().generic(),(_,_) -> true,_ -> {});
                    runtime.set(MinecraftEffectRuntime.install(level,SlowGameTest.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),world::apply,MinecraftEffectRuntime::nativeSource));source.set(SlowGameTest.source(owner,10));runtime.get().bind(source.get());
                });
                connection.waitForClientboundPackets();context.waitFor(client->client.player.onGround()&&client.player.position().distanceTo(start.get())<.1);
                context.getInput().holdKey(o->o.keyUp);context.waitTicks(8);context.runOnClient(client->sample.set(client.player.position()));context.waitTicks(20);context.runOnClient(client->{baseline.set(client.player.getZ()-sample.get().z);if(baseline.get()<1)throw new AssertionError("Baseline ground input did not move");});
                game.getServer().runOnServer(server->{SlowGameTest.event(runtime.get(),source.get(),connection.getServerPlayer(),"apply_slow",40);SlowGameTest.event(runtime.get(),source.get(),combatant.get(),"apply_slow",40);});
                context.waitFor(client->projected(client.player.getAttribute(Attributes.MOVEMENT_SPEED),-.5)&&projected(client.player.getAttribute(Attributes.JUMP_STRENGTH),-.3));
                context.waitFor(client->client.level.getEntity(combatant.get().getId()) instanceof LivingEntity e&&projected(e.getAttribute(Attributes.MOVEMENT_SPEED),-.5));
                context.waitTicks(8);context.runOnClient(client->sample.set(client.player.position()));context.waitTicks(20);context.runOnClient(client->{double distance=client.player.getZ()-sample.get().z;if(distance<baseline.get()*.3||distance>baseline.get()*.7)throw new AssertionError("Ground movement was not reduced: normal="+baseline.get()+" slow="+distance);if(NativeMovementGameTest.mask(client.player)!=0)throw new AssertionError("Slow blocked ordinary input");});
                connection.waitForServerboundPackets();game.getServer().runOnServer(server->{var player=connection.getServerPlayer();if(player.getZ()<start.get().z+2)throw new AssertionError("Server did not accept ground movement");SlowGameTest.event(runtime.get(),source.get(),player,"apply_slow",60);});
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==15&&cleared(client.player.getAttribute(Attributes.MOVEMENT_SPEED))&&cleared(client.player.getAttribute(Attributes.JUMP_STRENGTH)));
                context.runOnClient(client->sample.set(client.player.position()));context.waitTicks(8);context.runOnClient(client->{if(client.player.position().distanceTo(sample.get())>1e-5)throw new AssertionError("Held input moved frozen player");});
                game.getServer().runOnServer(server->SlowGameTest.event(runtime.get(),source.get(),connection.getServerPlayer(),"clear_freeze",0));
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==0&&client.player.getZ()>sample.get().z+.2);
                context.getInput().releaseKey(o->o.keyUp);
                context.runOnClient(client->{var npc=client.level.getEntity(combatant.get().getId());if(!(npc instanceof LivingEntity e)||!projected(e.getAttribute(Attributes.MOVEMENT_SPEED),-.5))throw new AssertionError("Player thaw removed another target's Slow");});
                game.getServer().runOnServer(server->{SlowGameTest.event(runtime.get(),source.get(),combatant.get(),"clear_slow",0);if(runtime.get().failure().isPresent())throw new AssertionError(runtime.get().failure());});
                context.waitFor(client->client.level.getEntity(combatant.get().getId()) instanceof LivingEntity e&&cleared(e.getAttribute(Attributes.MOVEMENT_SPEED)));
            }finally{
                context.getInput().releaseKey(o->o.keyUp);game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();if(caster.get()!=null)caster.get().discard();if(combatant.get()!=null)combatant.get().discard();});
            }
        }
    }
}
