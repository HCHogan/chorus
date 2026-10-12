package com.imdomestic.chorus;

import com.imdomestic.chorus.client.WeaponChargeClient;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.network.WeaponChargePayloads.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.test.WeaponChargeInputGameTest;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

/** Real held input authorizes a full-trigger shot; completion cannot rearm until physical key-up. */
public final class WeaponAutoChargeClientGameTest implements FabricClientGameTest {
    private static void focus(net.minecraft.client.Minecraft client,boolean focused){((com.imdomestic.chorus.test.mixin.WindowFocusAccessor)(Object)client.getWindow()).chorus$focused(focused);}
    @Override public void runTest(ClientGameTestContext context){
        context.getInput().resizeWindow(1280,720);
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();
            var runtime=new AtomicReference<MinecraftEffectRuntime>();var pause=new AtomicReference<Boolean>();var shots=new AtomicInteger();
            try{
                context.runOnClient(client->{pause.set(client.options.pauseOnLostFocus);client.options.pauseOnLostFocus=false;client.player.setXRot(-90);});
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();player.setGameMode(GameType.SURVIVAL);player.setXRot(-90);var level=player.level();
                    var world=new MinecraftWorldActions(level,id->level.getEntity(UUID.fromString(id)) instanceof net.minecraft.world.entity.LivingEntity entity?entity:null,_ -> level.damageSources().generic(),(_,_) -> true,_ -> {});
                    runtime.set(MinecraftEffectRuntime.install(level,WeaponChargeInputGameTest.program(true),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{var result=world.apply(request);if(result instanceof ProjectileFlight.Receipt receipt&&receipt.entity().isPresent())shots.incrementAndGet();return result;},MinecraftEffectRuntime::nativeSource));
                    var stack=new ItemStack(Items.BOW);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear("client-auto-charge","test:bow",Map.of("perk","normal")));
                    player.getInventory().setItem(0,stack);var equipment=PlayerEquipment.get(player);equipment.swap(player,"test:primary",0,equipment.revision());equipment.draw(player,Optional.of("test:primary"),equipment.revision());
                });
                connection.waitForClientboundPackets();
                context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(View::held).isPresent());
                context.getInput().releaseKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(v->!v.held()&&v.status()==Status.CANCELLED).isPresent());
                context.waitTicks(45);
                game.getServer().runOnServer(server->{if(shots.get()!=0||runtime.get().state().engine().domain().ammunition().get("client-auto-charge").magazine()!=10)throw new AssertionError("Early release fired a partial or delayed shot");});

                context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(View::held).isPresent());
                context.takeScreenshot("chorus-auto-charge-progress");
                context.waitFor(client->WeaponChargeClient.view().filter(v->!v.held()&&v.status()==Status.FIRED).isPresent());
                context.waitTicks(55);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(shots.get()!=1||!runtime.get().state().engine().domain().charging().isEmpty()||runtime.get().state().engine().domain().ammunition().get("client-auto-charge").magazine()!=9)throw new AssertionError("Held key did not produce exactly one automatic shot");});
                context.getInput().releaseKey(InputConstants.KEY_R);context.waitTick();

                context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(View::held).isPresent());
                context.runOnClient(client->client.gui.setScreen(new InventoryScreen(client.player)));context.waitTicks(3);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(!runtime.get().state().engine().domain().charging().isEmpty()||shots.get()!=1)throw new AssertionError("GUI did not cancel automatic charge");});
                context.runOnClient(client->client.gui.setScreen(null));context.waitTicks(45);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(!runtime.get().state().engine().domain().charging().isEmpty()||shots.get()!=1)throw new AssertionError("GUI close rearmed held key");});
                context.getInput().releaseKey(InputConstants.KEY_R);context.waitTick();

                context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(View::held).isPresent());
                context.runOnClient(client->focus(client,false));context.waitTicks(3);connection.waitForServerboundPackets();
                context.runOnClient(client->{if(WeaponChargeClient.view().isPresent())throw new AssertionError("Focus loss retained automatic charge HUD");});
                game.getServer().runOnServer(server->{if(!runtime.get().state().engine().domain().charging().isEmpty()||shots.get()!=1)throw new AssertionError("Focus loss retained automatic charge");});
                context.runOnClient(client->focus(client,true));context.waitTicks(45);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(!runtime.get().state().engine().domain().charging().isEmpty()||shots.get()!=1)throw new AssertionError("Focus gain rearmed held key");});
                context.getInput().releaseKey(InputConstants.KEY_R);context.waitTick();

                context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(View::held).isPresent());
                context.waitFor(client->WeaponChargeClient.view().filter(v->!v.held()&&v.status()==Status.FIRED).isPresent());
                context.getInput().releaseKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(shots.get()!=2||runtime.get().state().engine().domain().ammunition().get("client-auto-charge").magazine()!=8||runtime.get().failure().isPresent())throw new AssertionError("Fresh press did not rearm exactly once");});
            }finally{
                context.getInput().releaseKey(InputConstants.KEY_R);
                context.runOnClient(client->{focus(client,true);if(pause.get()!=null)client.options.pauseOnLostFocus=pause.get();client.gui.setScreen(null);});
                game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();});
            }
        }
    }
}
