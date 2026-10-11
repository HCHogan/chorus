package com.imdomestic.chorus;

import com.imdomestic.chorus.client.WeaponAimClient;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.network.WeaponAimPayloads.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.test.WeaponAimInputGameTest;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

/** Real registered keyboard and rebound mouse inputs, held lease, server perk and asynchronous projection. */
public final class WeaponAimClientGameTest implements FabricClientGameTest {
    private static void focus(net.minecraft.client.Minecraft client, boolean focused) {
        // The framework cancels native onFocus events. Exercise the production reader, tick and packets against the actual flag.
        ((com.imdomestic.chorus.test.mixin.WindowFocusAccessor) (Object) client.getWindow()).chorus$focused(focused);
    }
    @Override public void runTest(ClientGameTestContext context) {
        try (var game = context.worldBuilder().create()) {
            var connection = game.getConnection(); connection.waitForChunksRender();
            var runtime = new AtomicReference<MinecraftEffectRuntime>(); var aimKey = new AtomicReference<KeyMapping>(); var useKey = new AtomicReference<String>(); var pauseOnLostFocus = new AtomicReference<Boolean>();
            try {
                context.runOnClient(client -> { pauseOnLostFocus.set(client.options.pauseOnLostFocus); client.options.pauseOnLostFocus = false; });
                game.getServer().runOnServer(server -> {
                    var player = connection.getServerPlayer(); player.setGameMode(GameType.SURVIVAL); String holder = player.getUUID().toString();
                    var world = new MinecraftWorldActions(player.level(), id -> id.equals(holder) ? player : null, _ -> player.level().damageSources().generic(), (_, _) -> true, _ -> {});
                    runtime.set(MinecraftEffectRuntime.install(player.level(), WeaponAimInputGameTest.program(), EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world, MinecraftEffectRuntime::nativeSource));
                    var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear("client-aim", "test:rifle", Map.of("perk", "normal")));
                    player.getInventory().setItem(0, stack); var equipment = PlayerEquipment.get(player); equipment.swap(player, "test:primary", 0, equipment.revision()); equipment.draw(player, Optional.of("test:primary"), equipment.revision());
                });
                connection.waitForClientboundPackets(); context.getInput().holdKey(InputConstants.KEY_Z); connection.waitForServerboundPackets();
                context.waitFor(client -> WeaponAimClient.view().filter(v -> v.held() && v.phase() == Phase.AIMED && v.weapon().equals("client-aim")).isPresent());
                context.waitTicks(50); connection.waitForServerboundPackets();
                game.getServer().runOnServer(server -> {
                    var state = runtime.get().state().engine().domain();
                    if (state.aiming().isEmpty() || state.buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("chorus_d2:box_breathing"))) throw new AssertionError("Real held input expired or heartbeat restarted Box Breathing");
                });
                context.runOnClient(client -> {
                    var current = WeaponAimClient.view().orElseThrow();
                    WeaponAimClient.accept(new View(current.revision(), current.sequence(), current.gesture(), current.player(), current.dimension(), false, Status.IDLE, Phase.OFF, "", 0, 0));
                    if (!WeaponAimClient.view().orElseThrow().equals(current)) throw new AssertionError("Old view revision replaced current phase");
                    WeaponAimClient.accept(new View(current.revision()+1, current.sequence(), current.gesture(), current.player(), "test:elsewhere", false, Status.IDLE, Phase.OFF, "", 0, 0));
                    if (!WeaponAimClient.view().orElseThrow().equals(current)) throw new AssertionError("Other dimension replaced current phase");
                    client.gui.setScreen(new InventoryScreen(client.player));
                });
                context.waitTicks(3); connection.waitForServerboundPackets();
                game.getServer().runOnServer(server -> { if (!runtime.get().state().engine().domain().aiming().isEmpty()) throw new AssertionError("GUI did not cancel server aim"); });
                context.runOnClient(client -> client.gui.setScreen(null)); context.waitTicks(5); connection.waitForServerboundPackets();
                game.getServer().runOnServer(server -> { if (!runtime.get().state().engine().domain().aiming().isEmpty()) throw new AssertionError("GUI close revived cancelled hold"); });
                context.getInput().releaseKey(InputConstants.KEY_Z); context.waitTick(); context.getInput().holdKey(InputConstants.KEY_Z); connection.waitForServerboundPackets();
                context.waitFor(client -> WeaponAimClient.view().filter(v -> v.held() && v.phase() == Phase.AIMED).isPresent());
                context.runOnClient(client -> focus(client, false)); context.waitTicks(3); connection.waitForServerboundPackets();
                context.runOnClient(client -> { if (client.isWindowActive() || WeaponAimClient.view().isPresent()) throw new AssertionError("Focus loss retained active projection"); });
                game.getServer().runOnServer(server -> { if (!runtime.get().state().engine().domain().aiming().isEmpty()) throw new AssertionError("Focus loss retained server aim"); });
                context.runOnClient(client -> focus(client, true)); context.waitTicks(4); connection.waitForServerboundPackets();
                game.getServer().runOnServer(server -> { if (!runtime.get().state().engine().domain().aiming().isEmpty()) throw new AssertionError("Focus gain replayed old hold"); });
                context.getInput().releaseKey(InputConstants.KEY_Z); context.waitTick();
                // Test actual right-button rebinding without invoking vanilla item use in the same fixture.
                context.runOnClient(client -> {
                    aimKey.set(Arrays.stream(client.options.keyMappings).filter(k -> k.getName().equals("key.chorus.aim")).findFirst().orElseThrow());
                    useKey.set(client.options.keyUse.saveString()); client.options.keyUse.setKey(InputConstants.UNKNOWN);
                    aimKey.get().setKey(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT)); KeyMapping.resetMapping();
                });
                context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT); connection.waitForServerboundPackets();
                context.waitFor(client -> WeaponAimClient.view().filter(v -> v.held() && v.phase() == Phase.AIMED).isPresent());
                context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT); connection.waitForServerboundPackets();
                context.waitFor(client -> WeaponAimClient.view().filter(v -> !v.held() && v.phase() == Phase.OFF).isPresent());
                game.getServer().runOnServer(server -> { if (runtime.get().failure().isPresent()) throw new AssertionError("Input runtime failed"); });
            } finally {
                context.getInput().releaseKey(InputConstants.KEY_Z); context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);
                context.runOnClient(client -> {
                    focus(client, true); if (pauseOnLostFocus.get() != null) client.options.pauseOnLostFocus = pauseOnLostFocus.get();
                    client.gui.setScreen(null);
                    if (aimKey.get() != null) { aimKey.get().setKey(aimKey.get().getDefaultKey()); client.options.keyUse.setKey(InputConstants.getKey(useKey.get())); KeyMapping.resetMapping(); }
                });
                game.getServer().runOnServer(server -> { if (runtime.get() != null) runtime.get().close(); });
            }
        }
    }
}
