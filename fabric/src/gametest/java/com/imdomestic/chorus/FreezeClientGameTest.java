package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.test.NativeMovementGameTest;
import com.imdomestic.chorus.test.FreezeGameTest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Actual Freeze data restricts held inputs and physical motion, then releases local and tracked entities independently. */
public final class FreezeClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context){
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();var runtime=new AtomicReference<MinecraftEffectRuntime>();var source=new AtomicReference<EffectSource>();var caster=new AtomicReference<Mob>();var combatant=new AtomicReference<Mob>();var start=new AtomicReference<Vec3>();
            try{
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();var level=player.level();var center=player.blockPosition().above(5);
                    for(int x=-5;x<=10;x++)for(int z=-5;z<=15;z++){
                        level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.STONE.defaultBlockState());for(int y=0;y<8;y++)level.setBlockAndUpdate(center.offset(x,y,z),Blocks.AIR.defaultBlockState());
                    }
                    start.set(new Vec3(center.getX()+.5,center.getY()+2,center.getZ()+.5));player.setGameMode(GameType.SURVIVAL);player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);
                    var owner=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);owner.setPos(start.get().add(-3,0,0));owner.setNoAi(true);owner.setNoGravity(true);level.addFreshEntity(owner);caster.set(owner);
                    var npc=EntityTypes.SKELETON.create(level,EntitySpawnReason.COMMAND);npc.setPos(start.get().add(4,0,0));npc.removeFreeWill();npc.setItemSlot(EquipmentSlot.HEAD,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARVED_PUMPKIN));npc.addTag("chorus_d2:rank_and_file");level.addFreshEntity(npc);combatant.set(npc);
                    var world=new MinecraftWorldActions(level,id->{var e=level.getEntity(UUID.fromString(id));return e instanceof LivingEntity living?living:null;},_->level.damageSources().generic(),(_,_) -> true,_ -> {});
                    runtime.set(MinecraftEffectRuntime.install(level,FreezeGameTest.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),world::apply,MinecraftEffectRuntime::nativeSource));source.set(FreezeGameTest.source(owner));runtime.get().bind(source.get());
                    FreezeGameTest.event(runtime.get(),source.get(),player,"apply_freeze");FreezeGameTest.event(runtime.get(),source.get(),npc,"apply_freeze");
                });
                context.getInput().holdKey(o->o.keyUp);context.getInput().holdKey(o->o.keyJump);
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==15&&client.player.position().distanceTo(start.get())<1e-5);
                context.waitFor(client->client.level.getEntity(combatant.get().getId()) instanceof LivingEntity e&&NativeMovementGameTest.mask(e)==15&&e.position().distanceTo(start.get().add(4,0,0))<1e-5);
                context.waitTicks(8);connection.waitForServerboundPackets();
                context.runOnClient(client->{if(NativeMovementGameTest.mask(client.player)!=15||client.player.position().distanceTo(start.get())>1e-5||client.player.getDeltaMovement().length()>1e-5)throw new AssertionError("Held player input or gravity escaped Freeze");});
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();if(player.position().distanceTo(start.get())>1e-5||combatant.get().position().distanceTo(start.get().add(4,0,0))>1e-5)throw new AssertionError("Server Freeze anchor moved");
                    if(runtime.get().failure().isPresent())throw new AssertionError(runtime.get().failure());FreezeGameTest.event(runtime.get(),source.get(),player,"clear_freeze");
                });
                context.getInput().releaseKey(o->o.keyJump);
                context.waitFor(client->NativeMovementGameTest.mask(client.player)==0&&client.player.getZ()>start.get().z+.1&&client.player.getY()<start.get().y-.1);
                context.runOnClient(client->{var npc=client.level.getEntity(combatant.get().getId());if(!(npc instanceof LivingEntity e)||NativeMovementGameTest.mask(e)!=15)throw new AssertionError("Player cleanse also thawed remote combatant");});
                context.getInput().releaseKey(o->o.keyUp);
                game.getServer().runOnServer(server->{FreezeGameTest.event(runtime.get(),source.get(),combatant.get(),"clear_freeze");if(NativeMovementGameTest.mask(combatant.get())!=0||combatant.get().isNoAi())throw new AssertionError("Cleared NPC cannot resume physics");});
                context.waitFor(client->client.level.getEntity(combatant.get().getId()) instanceof LivingEntity e&&NativeMovementGameTest.mask(e)==0&&e.getY()<start.get().y-.1);
            }finally{
                context.getInput().releaseKey(o->o.keyUp);context.getInput().releaseKey(o->o.keyJump);game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();if(caster.get()!=null)caster.get().discard();if(combatant.get()!=null)combatant.get().discard();});
            }
        }
    }
}
