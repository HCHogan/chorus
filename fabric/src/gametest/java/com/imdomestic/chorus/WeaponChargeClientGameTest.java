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
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

/** Actual registered key/mouse input, native projectile launch, server progress, GUI/focus and rendered HUD. */
public final class WeaponChargeClientGameTest implements FabricClientGameTest {
    private static void focus(net.minecraft.client.Minecraft client,boolean focused){((com.imdomestic.chorus.test.mixin.WindowFocusAccessor)(Object)client.getWindow()).chorus$focused(focused);}
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280,720);
        try(var game=context.worldBuilder().create()){
            var connection=game.getConnection();connection.waitForChunksRender();
            var runtime=new AtomicReference<MinecraftEffectRuntime>();var key=new AtomicReference<KeyMapping>();var useKey=new AtomicReference<String>();var pause=new AtomicReference<Boolean>();var shots=new AtomicInteger();
            try{
                context.runOnClient(client->{pause.set(client.options.pauseOnLostFocus);client.options.pauseOnLostFocus=false;client.player.setXRot(-90);});
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();player.setGameMode(GameType.SURVIVAL);player.setXRot(-90);String holder=player.getUUID().toString();
                    var level=player.level();
                    var world=new MinecraftWorldActions(level,id->level.getEntity(UUID.fromString(id)) instanceof net.minecraft.world.entity.LivingEntity entity?entity:null,_ -> level.damageSources().generic(),(_,_) -> true,_ -> {});
                    runtime.set(MinecraftEffectRuntime.install(player.level(),WeaponChargeInputGameTest.program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{var result=world.apply(request);if(result instanceof ProjectileFlight.Receipt receipt&&receipt.entity().isPresent())shots.incrementAndGet();return result;},MinecraftEffectRuntime::nativeSource));
                    var stack=new ItemStack(Items.BOW);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear("client-charge","test:bow",Map.of("perk","normal")));
                    player.getInventory().setItem(0,stack);var equipment=PlayerEquipment.get(player);equipment.swap(player,"test:primary",0,equipment.revision());equipment.draw(player,Optional.of("test:primary"),equipment.revision());
                });
                connection.waitForClientboundPackets();context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(v->v.held()&&v.phase()==Phase.READY&&v.weapon().equals("client-charge")).isPresent());
                context.waitTicks(45);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(runtime.get().state().engine().domain().charging().isEmpty()||shots.get()!=0)throw new AssertionError("Held charge expired or automatically fired");});
                context.takeScreenshot("chorus-charge-ready");
                context.runOnClient(client->{
                    var v=WeaponChargeClient.view().orElseThrow();
                    WeaponChargeClient.accept(new View(v.revision(),v.sequence(),v.gesture(),v.player(),v.entity(),v.dimension(),false,Status.IDLE,Phase.OFF,"",0,0,0,0));
                    if(!WeaponChargeClient.view().orElseThrow().equals(v))throw new AssertionError("Old revision replaced progress");
                    WeaponChargeClient.accept(new View(v.revision()+1,v.sequence(),v.gesture(),v.player(),v.entity()+1,v.dimension(),false,Status.IDLE,Phase.OFF,"",0,0,0,0));
                    if(!WeaponChargeClient.view().orElseThrow().equals(v))throw new AssertionError("Other player incarnation replaced progress");
                });
                context.getInput().releaseKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(v->!v.held()&&v.status()==Status.FIRED).isPresent());
                game.getServer().runOnServer(server->{if(shots.get()!=1||runtime.get().state().engine().domain().ammunition().get("client-charge").magazine()!=9)throw new AssertionError("Actual release did not launch/pay once");});
                context.waitTicks(5);context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(View::held).isPresent());
                context.runOnClient(client->client.gui.setScreen(new InventoryScreen(client.player)));context.waitTicks(3);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(!runtime.get().state().engine().domain().charging().isEmpty()||shots.get()!=1)throw new AssertionError("GUI did not cancel without firing");});
                context.runOnClient(client->client.gui.setScreen(null));context.waitTicks(5);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(!runtime.get().state().engine().domain().charging().isEmpty())throw new AssertionError("GUI close revived held charge");});
                context.getInput().releaseKey(InputConstants.KEY_R);context.waitTick();context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(View::held).isPresent());
                context.runOnClient(client->focus(client,false));context.waitTicks(3);connection.waitForServerboundPackets();
                context.runOnClient(client->{if(WeaponChargeClient.view().isPresent())throw new AssertionError("Focus loss retained HUD projection");});
                game.getServer().runOnServer(server->{if(!runtime.get().state().engine().domain().charging().isEmpty()||shots.get()!=1)throw new AssertionError("Focus loss fired/retained charge");});
                context.runOnClient(client->focus(client,true));context.waitTicks(4);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{if(!runtime.get().state().engine().domain().charging().isEmpty())throw new AssertionError("Focus gain revived charge");});
                context.getInput().releaseKey(InputConstants.KEY_R);context.waitTick();
                context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();context.waitFor(client->WeaponChargeClient.view().filter(View::held).isPresent());
                var oldPlayer=new AtomicReference<net.minecraft.client.player.LocalPlayer>();var oldView=new AtomicReference<View>();
                context.runOnClient(client->{oldPlayer.set(client.player);oldView.set(WeaponChargeClient.view().orElseThrow());});
                game.getServer().runOnServer(server->{var player=connection.getServerPlayer();player.setHealth(0);player.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));});
                connection.waitForClientboundPackets();context.waitFor(client->client.player!=null&&client.player!=oldPlayer.get());context.waitTicks(3);connection.waitForServerboundPackets();
                context.runOnClient(client->{var v=oldView.get();if(client.player.getId()!=v.entity())throw new AssertionError("Fixture must cover native entity-id reuse");WeaponChargeClient.accept(new View(v.revision()+100,v.sequence(),v.gesture(),v.player(),v.entity(),v.dimension(),v.held(),v.status(),v.phase(),v.weapon(),v.startedAt(),v.minimumAt(),v.fullAt(),v.observedAt()));if(WeaponChargeClient.view().filter(View::held).isPresent())throw new AssertionError("Old life projection survived respawn");client.player.setXRot(-90);});
                game.getServer().runOnServer(server->{if(!runtime.get().state().engine().domain().charging().isEmpty()||shots.get()!=1)throw new AssertionError("Respawn retained or fired old charge");});
                context.getInput().releaseKey(InputConstants.KEY_R);context.waitTick();
                context.runOnClient(client->{key.set(Arrays.stream(client.options.keyMappings).filter(k->k.getName().equals("key.chorus.charge")).findFirst().orElseThrow());useKey.set(client.options.keyUse.saveString());client.options.keyUse.setKey(InputConstants.UNKNOWN);key.get().setKey(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT));KeyMapping.resetMapping();});
                context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(v->v.held()&&v.phase()==Phase.READY).isPresent());
                context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(v->v.status()==Status.FIRED&&!v.held()).isPresent());
                game.getServer().runOnServer(server->{var ammo=runtime.get().state().engine().domain().ammunition().get("client-charge").magazine();if(shots.get()!=2||ammo!=8||runtime.get().failure().isPresent())throw new AssertionError("Rebound mouse after respawn: launches="+shots.get()+", ammo="+ammo+", failure="+runtime.get().failure());});
            }finally{
                context.getInput().releaseKey(InputConstants.KEY_R);context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);
                context.runOnClient(client->{focus(client,true);if(pause.get()!=null)client.options.pauseOnLostFocus=pause.get();client.gui.setScreen(null);if(key.get()!=null){key.get().setKey(key.get().getDefaultKey());client.options.keyUse.setKey(InputConstants.getKey(useKey.get()));KeyMapping.resetMapping();}});
                game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();});
            }
        }
    }
}
