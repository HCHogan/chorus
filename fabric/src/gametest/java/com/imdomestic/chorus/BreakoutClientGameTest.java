package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.combat.HealthPayment;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.test.BreakoutGameTest;
import com.imdomestic.chorus.test.FreezeGameTest;
import com.imdomestic.chorus.test.NativeMovementGameTest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** A normal client's class command pays health after a calibrated delay and restores held movement. */
public final class BreakoutClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context){
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();var runtime=new AtomicReference<MinecraftEffectRuntime>();var caster=new AtomicReference<Mob>();var combatant=new AtomicReference<Mob>();var start=new AtomicReference<Vec3>();var payments=new ArrayList<HealthPayment.Receipt>();var cues=new ArrayList<com.imdomestic.chorus.effect.data.Action.CueCommand>();
            try{
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();var level=player.level();var center=player.blockPosition().above(5);
                    for(int x=-5;x<=10;x++)for(int z=-5;z<=25;z++){level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());for(int y=0;y<8;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());}
                    start.set(new Vec3(center.getX()+.5,center.getY()+2,center.getZ()+.5));player.setGameMode(GameType.SURVIVAL);player.setHealth(20);player.getFoodData().setFoodLevel(10);player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);
                    var owner=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);owner.setPos(start.get().add(-3,0,0));owner.setNoAi(true);owner.setNoGravity(true);level.addFreshEntity(owner);caster.set(owner);
                    var npc=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);npc.setPos(start.get().add(4,0,0));npc.removeFreeWill();npc.addTag("chorus_d2:elite");level.addFreshEntity(npc);combatant.set(npc);
                    var world=new MinecraftWorldActions(level,id->{var entity=level.getEntity(UUID.fromString(id));return entity instanceof LivingEntity living?living:null;},_->level.damageSources().generic(),(_,_) -> true,cues::add);
                    runtime.set(MinecraftEffectRuntime.install(level,BreakoutGameTest.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{var result=world.apply(request);if(result instanceof HealthPayment.Receipt r)payments.add(r);return result;},MinecraftEffectRuntime::nativeSource));
                    BreakoutGameTest.select(runtime.get(),player);runtime.get().bind(BreakoutGameTest.calibration(player,1.5));var source=FreezeGameTest.source(owner);runtime.get().bind(source);FreezeGameTest.event(runtime.get(),source,player,"apply_freeze");FreezeGameTest.event(runtime.get(),source,npc,"apply_freeze");
                });
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==15&&client.player.position().distanceTo(start.get())<1e-5&&client.player.getHealth()==20);
                context.getInput().holdKey(o->o.keyUp);
                context.runOnClient(client->{client.player.connection.sendCommand("chorus ability use chorus_d2:class");client.player.connection.sendCommand("chorus ability use chorus_d2:class");});
                connection.waitForServerboundPackets();context.waitTicks(5);
                game.getServer().runOnServer(server->{if(!payments.isEmpty()||NativeMovementGameTest.mask(connection.getServerPlayer())!=15)throw new AssertionError("Breakout paid before its calibrated delay");if(runtime.get().failure().isPresent())throw new AssertionError(runtime.get().failure());});
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==0&&Math.abs(client.player.getHealth()-15)<1e-5&&client.player.getZ()>start.get().z+.2);
                context.getInput().releaseKey(o->o.keyUp);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(payments.size()!=1||!payments.getFirst().command().target().equals(connection.getServerPlayer().getUUID().toString()))throw new AssertionError("Breakout was repeated or charged another entity");if(NativeMovementGameTest.mask(combatant.get())!=15)throw new AssertionError("Breakout thawed another target");if(connection.getServerPlayer().getZ()<start.get().z+.1)throw new AssertionError("Server did not accept released movement");});
                context.runOnClient(client->client.player.connection.sendCommand("chorus ability use chorus_d2:class"));connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(cues.stream().filter(c->c.cue().equals("test:paid_class")).count()!=1)throw new AssertionError("Original class ability or its energy was not restored");if(payments.size()!=1||runtime.get().failure().isPresent())throw new AssertionError("Unexpected additional payment or runtime failure");});
            }finally{
                context.getInput().releaseKey(o->o.keyUp);game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();if(caster.get()!=null)caster.get().discard();if(combatant.get()!=null)combatant.get().discard();});
            }
        }
    }
}
