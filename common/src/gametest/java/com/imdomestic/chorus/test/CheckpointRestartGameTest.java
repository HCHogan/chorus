package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/** Opt-in two-process test. Normal gates check startup invalidation without leaving a runtime installed. */
public class CheckpointRestartGameTest {
    private static final Identifier PROGRAM = Identifier.parse("chorus_gametest:checkpoint_restart");
    private static RuleEngine.Signal cast(String holder) {
        return new RuleEngine.Signal("test:restart_cast", new EffectEvent(holder, holder, new BuffInstance.Origin(holder, "skill", "", ""), Set.of(), Map.of()));
    }
    @GameCase(environment = "chorus_gametest:checkpoint_restart", maxTicks = 400)
    public void checkpointRestartUsesNativeFilesAndAutomaticLifecycle(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer(); var status = MinecraftCheckpointLifecycle.status(server).orElseThrow();
        h.assertTrue(status.started() && !status.stopping(), "server startup lifecycle not active");
        var disk = JsonParser.parseString(Files.readString(status.file())).getAsJsonObject();
        h.assertValueEqual(disk.get("phase").getAsString(), "RUNNING", "old recoverable mark must be invalidated before tests/world work");
        String phase = System.getProperty("chorus.test.restart.phase");
        if (phase == null) { h.succeed(); return; }
        var metadata = server.getWorldPath(LevelResource.ROOT).resolve("chorus-restart-fixture-player.txt");
        if (phase.startsWith("save")) {
            h.assertTrue(status.restoredDimensions().isEmpty(), "save phase reused an old runtime");
            var player = NativeMeleeGameTest.player(h); String holder = player.getUUID().toString();
            var runtime = EffectPrograms.install(h.getLevel(), PROGRAM, EffectState.Mode.PVP);
            EffectPrograms.install(server.getLevel(Level.NETHER), PROGRAM, EffectState.Mode.PVE);
            var item = new ItemStack(Items.DIAMOND_CHESTPLATE);
            item.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear("restart-armor", "test:arms", Map.of()));
            player.getInventory().setItem(0, item); var equipment = PlayerEquipment.get(player);
            equipment.swap(player, "test:arms", 0, equipment.revision());
            PlayerActor.get(player).configure(player, Optional.of(new com.imdomestic.chorus.effect.actor.ActorBinding("test:restart_actor")), 0);
            player.setHealth(27);
            runtime.bind(new EffectSource("skill", "test:restart_skill", holder, new BuffInstance.Origin(holder, "skill", "", ""), Set.of()));
            runtime.start(cast(holder)); h.assertValueEqual(player.getHealth(), 29f, "initialize once and pay for one cast");
            h.assertValueEqual(player.getMaxHealth(), 30f, "equipped temporary capacity");
            Files.writeString(metadata, holder);
            if (phase.equals("save_failure")) {
                MinecraftWorldSave.save(server).requireSuccess();
                var paths = (MinecraftPlayerSaveAccess) MinecraftWorldSave.bindings(server).playerStorage();
                var backup = paths.chorus$playerFile(player.getUUID()).resolveSibling(holder + ".dat_old");
                h.assertTrue(!Files.exists(backup), "fixture backup already exists");
                Files.createDirectory(backup); Files.writeString(backup.resolve("occupied"), "owned checkpoint restart obstruction");
            }
            // Deliberately leave real players and both runtimes for the production shutdown lifecycle.
        } else if (phase.equals("restore")) {
            h.assertValueEqual(status.restoredDimensions(), Set.of("minecraft:overworld", "minecraft:the_nether"), "both dimensions restored before tests");
            var runtime = MinecraftEffectRuntime.installed(h.getLevel()).orElseThrow();
            var nether = MinecraftEffectRuntime.installed(server.getLevel(Level.NETHER)).orElseThrow();
            h.assertValueEqual(runtime.state().engine().domain().mode(), EffectState.Mode.PVP, "overworld mode preserved");
            h.assertValueEqual(nether.state().engine().domain().mode(), EffectState.Mode.PVE, "nether mode preserved");
            var holder = UUID.fromString(Files.readString(metadata));
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(holder, "melee-test"), false);
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND); new io.netty.channel.embedded.EmbeddedChannel(connection);
            // 26.3 loads player NBT in PrepareSpawnTask, before PlayerList.placeNewPlayer indexes it.
            var spawn = new net.minecraft.server.network.config.PrepareSpawnTask(server, new net.minecraft.server.players.NameAndId(holder, "melee-test"));
            spawn.start(_ -> {});
            // GameTest advances hundreds of ticks per wall-clock second. Pump the real server/chunk task queues
            // while waiting for asynchronous generation instead of timing out on an artificial tick budget.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            server.managedBlock(() -> {
                if (spawn.tick()) return true;
                if (System.nanoTime() > deadline) throw new IllegalStateException("Actual restart spawn chunks did not finish within 30 seconds");
                return false;
            });
            var player = spawn.spawnPlayer(connection, cookie);
            player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            runtime.prepare();
            h.assertValueEqual(PlayerActor.get(player).snapshot().binding(), Optional.of(new com.imdomestic.chorus.effect.actor.ActorBinding("test:restart_actor")), "actual player actor file");
            h.assertValueEqual(runtime.state().engine().domain().actors().get(holder.toString()), PlayerActor.get(player).snapshot().binding().orElseThrow(), "saved actor/domain projection");
            h.assertTrue(runtime.checkpoint().actorOwners().contains(holder), "restored player actor ownership");
            h.assertValueEqual(player.getMaxHealth(), 30f, "rules restored before real login/NBT clamped capacity");
            h.assertValueEqual(player.getHealth(), 29f, "real player NBT health and no initialization replay");
            h.assertValueEqual(PlayerEquipment.get(player).item("test:arms").get(ChorusComponents.EQUIPMENT.get()).instance(), "restart-armor", "actual player equipment file");
            h.assertValueEqual(runtime.state().engine().domain().resources().get(new ResourceState.Key(holder.toString(), "test:restart_energy")).value(), 0d, "paid resource stayed spent across process restart");
            runtime.start(cast(holder.toString())); h.assertValueEqual(player.getHealth(), 29f, "restart did not grant a free cast");
            h.assertTrue(runtime.failure().isEmpty(), "restored runtime failed on real login or cast");
        } else throw new IllegalArgumentException("Unexpected restart phase: " + phase);
        h.succeed();
    }
}
