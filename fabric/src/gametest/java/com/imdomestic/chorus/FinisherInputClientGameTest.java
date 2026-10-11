package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.test.FinisherInputGameTest;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Actual registered H key, C2S packet, server target selection and delayed death; GUI input never queues a cast. */
public final class FinisherInputClientGameTest implements FabricClientGameTest {
    private static LivingEntity target(net.minecraft.server.level.ServerLevel level, Vec3 point) {
        var cow = Objects.requireNonNull(EntityTypes.COW.create(level, EntitySpawnReason.COMMAND)); cow.setPos(point); cow.setNoAi(true); cow.setNoGravity(true);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); cow.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); cow.setHealth(20); cow.addTag("test:finishable");
        if (!level.addFreshEntity(cow)) throw new AssertionError("Could not create target"); return cow;
    }
    @Override public void runTest(ClientGameTestContext context) {
        try (var game = context.worldBuilder().create()) {
            var connection = game.getConnection(); connection.waitForChunksRender(); var start = new AtomicReference<Vec3>();
            var runtime = new AtomicReference<MinecraftEffectRuntime>(); var victim = new AtomicReference<LivingEntity>(); var starts = new AtomicInteger(); var completed = new AtomicInteger();
            try {
                game.getServer().runOnServer(server -> {
                    var player = connection.getServerPlayer(); var level = player.level(); var center = player.blockPosition().above(5);
                    for (int x=-4;x<=4;x++) for (int z=-3;z<=8;z++) { level.setBlockAndUpdate(center.offset(x,-1,z), Blocks.STONE.defaultBlockState()); for(int y=0;y<=4;y++) level.setBlockAndUpdate(center.offset(x,y,z), Blocks.AIR.defaultBlockState()); }
                    start.set(new Vec3(center.getX()+.5,center.getY(),center.getZ()+.5)); player.setGameMode(GameType.SURVIVAL); player.connection.teleport(start.get().x,start.get().y,start.get().z,0,0);
                    victim.set(target(level,start.get().add(0,0,2))); String holder = player.getUUID().toString();
                    var world = new MinecraftWorldActions(level, id -> level.getEntity(UUID.fromString(id)) instanceof LivingEntity e ? e : null,
                            _ -> level.damageSources().playerAttack(player), (_, _) -> true, cue -> { if(cue.cue().equals("test:started")) starts.incrementAndGet(); if(cue.cue().equals("test:completed")) completed.incrementAndGet(); });
                    runtime.set(MinecraftEffectRuntime.install(level, FinisherInputGameTest.program(), EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0,List.of())), world, MinecraftEffectRuntime::nativeSource));
                    runtime.get().bind(FinisherInputGameTest.input(player)); runtime.get().bind(FinisherInputGameTest.observer(player));
                });
                connection.waitForClientboundPackets(); context.waitFor(client -> client.player.onGround() && client.player.position().distanceTo(start.get()) < .1);
                context.getInput().pressKey(InputConstants.KEY_H); connection.waitForServerboundPackets(); context.waitFor(client -> completed.get() == 1);
                game.getServer().runOnServer(server -> { if(victim.get().isAlive() || starts.get()!=1 || runtime.get().failure().isPresent()) throw new AssertionError("Finisher key did not confirm one actual death"); victim.get().discard(); victim.set(target(connection.getServerPlayer().level(),start.get().add(0,0,2))); });
                context.runOnClient(client -> client.gui.setScreen(new InventoryScreen(client.player))); context.getInput().pressKey(InputConstants.KEY_H); context.waitTicks(8); connection.waitForServerboundPackets();
                game.getServer().runOnServer(server -> { if(starts.get()!=1 || !victim.get().isAlive()) throw new AssertionError("GUI input started finisher"); });
                context.runOnClient(client -> client.gui.setScreen(null)); context.waitTicks(4); connection.waitForServerboundPackets();
                if(starts.get()!=1) throw new AssertionError("Closing GUI replayed suppressed key");
                context.getInput().pressKey(InputConstants.KEY_H); connection.waitForServerboundPackets(); context.waitFor(client -> completed.get() == 2);
                game.getServer().runOnServer(server -> { if(starts.get()!=2 || victim.get().isAlive() || runtime.get().failure().isPresent()) throw new AssertionError("Fresh key after GUI did not execute exactly once"); });
            } finally {
                context.getInput().releaseKey(InputConstants.KEY_H); context.runOnClient(client -> client.gui.setScreen(null));
                game.getServer().runOnServer(server -> { if(runtime.get()!=null)runtime.get().close(); if(victim.get()!=null)victim.get().discard(); });
            }
        }
    }
}
