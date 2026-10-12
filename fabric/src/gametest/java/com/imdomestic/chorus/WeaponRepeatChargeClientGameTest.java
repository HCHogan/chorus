package com.imdomestic.chorus;

import static com.imdomestic.chorus.network.WeaponChargePayloads.*;
import com.imdomestic.chorus.client.WeaponChargeClient;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.test.WeaponChargeInputGameTest;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.JsonOps;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

/** One real key-down spans multiple server cycles; release and GUI cancel both charging and recovery. */
public final class WeaponRepeatChargeClientGameTest implements FabricClientGameTest {
    private static CompiledEffects program() {
        var data=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,WeaponChargeInputGameTest.program(true).program()).getOrThrow().getAsJsonObject();
        var weapon=data.getAsJsonArray("weapons").get(0).getAsJsonObject();var charging=weapon.getAsJsonObject("charging");charging.addProperty("repeat",true);
        charging.getAsJsonObject("full").getAsJsonObject("value").addProperty("value",.8);weapon.getAsJsonObject("fire").getAsJsonObject("interval").addProperty("value",.8);
        return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow();
    }
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280,720);
        try(var game=context.worldBuilder().create()) {
            var connection=game.getConnection();connection.waitForChunksRender();var runtime=new AtomicReference<MinecraftEffectRuntime>();var shots=new AtomicInteger();var stopped=new AtomicInteger();var gesture=new AtomicLong();
            try {
                context.runOnClient(client->client.player.setXRot(-90));
                game.getServer().runOnServer(server->{
                    var player=connection.getServerPlayer();player.setGameMode(GameType.SURVIVAL);player.setXRot(-90);var level=player.level();
                    var world=new MinecraftWorldActions(level,id->level.getEntity(UUID.fromString(id)) instanceof net.minecraft.world.entity.LivingEntity entity?entity:null,_ -> level.damageSources().generic(),(_,_) -> true,_ -> {});
                    runtime.set(MinecraftEffectRuntime.install(level,program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{var result=world.apply(request);if(result instanceof ProjectileFlight.Receipt r&&r.entity().isPresent())shots.incrementAndGet();return result;},MinecraftEffectRuntime::nativeSource));
                    var stack=new ItemStack(Items.BOW);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear("client-repeat-charge","test:bow",Map.of("perk","normal")));
                    player.getInventory().setItem(0,stack);var equipment=PlayerEquipment.get(player);equipment.swap(player,"test:primary",0,equipment.revision());equipment.draw(player,Optional.of("test:primary"),equipment.revision());
                });
                connection.waitForClientboundPackets();context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(v->v.held()&&v.phase()==Phase.CHARGING).isPresent());
                context.runOnClient(client->gesture.set(WeaponChargeClient.view().orElseThrow().gesture()));
                context.waitFor(client->shots.get()>=2&&WeaponChargeClient.view().filter(v->v.held()&&v.phase()==Phase.RECOVERING).isPresent());
                context.runOnClient(client->{var view=WeaponChargeClient.view().orElseThrow();if(view.gesture()!=gesture.get()||view.minimumAt()!=view.startedAt()||view.fraction()<0||view.fraction()>=1)throw new AssertionError("Recovery view lost the original gesture or phase timing");});
                context.takeScreenshot("chorus-repeat-charge-recovery");
                context.getInput().releaseKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(v->!v.held()&&v.status()==Status.CANCELLED).isPresent());
                game.getServer().runOnServer(server->{stopped.set(shots.get());if(!runtime.get().state().engine().domain().charging().isEmpty())throw new AssertionError("Release retained repeat state");});
                context.waitTicks(40);game.getServer().runOnServer(server->{if(shots.get()!=stopped.get())throw new AssertionError("Key release restarted a repeat cycle");});

                context.getInput().holdKey(InputConstants.KEY_R);connection.waitForServerboundPackets();
                context.waitFor(client->WeaponChargeClient.view().filter(v->v.held()&&v.phase()==Phase.RECOVERING).isPresent());
                context.runOnClient(client->client.gui.setScreen(new InventoryScreen(client.player)));context.waitTicks(3);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{stopped.set(shots.get());if(!runtime.get().state().engine().domain().charging().isEmpty())throw new AssertionError("GUI retained recovery intent");});
                context.runOnClient(client->client.gui.setScreen(null));context.waitTicks(40);connection.waitForServerboundPackets();
                game.getServer().runOnServer(server->{var state=runtime.get().state().engine().domain();if(shots.get()!=stopped.get()||!state.charging().isEmpty()||runtime.get().failure().isPresent()||state.ammunition().get("client-repeat-charge").magazine()!=10-shots.get())throw new AssertionError("GUI close rearmed physical hold or payment differs from actual shots");});
                context.getInput().releaseKey(InputConstants.KEY_R);context.waitTick();
            } finally {
                context.getInput().releaseKey(InputConstants.KEY_R);context.runOnClient(client->client.gui.setScreen(null));
                game.getServer().runOnServer(server->{if(runtime.get()!=null)runtime.get().close();});
            }
        }
    }
}
