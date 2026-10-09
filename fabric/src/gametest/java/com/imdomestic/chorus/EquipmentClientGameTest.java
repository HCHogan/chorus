package com.imdomestic.chorus;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.client.EquipmentScreen;
import com.imdomestic.chorus.client.EquipmentTheme;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A real integrated server, payload registration, key binding, rendered widgets and physical item exchange. */
public final class EquipmentClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> client.options.guiScale().set(2));
        context.getInput().resizeWindow(1280, 720);
        try (var game = context.worldBuilder().create()) {
            var connection = game.getConnection();
            connection.waitForChunksRender();
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var level = player.level();
                try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/equipment.json")), StandardCharsets.UTF_8)) {
                    var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
                    var world = new MinecraftWorldActions(level, id -> id.equals(player.getUUID().toString()) ? player : null,
                            _ -> level.damageSources().generic(), (_, _) -> true, _ -> {});
                    MinecraftEffectRuntime.install(level, program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world, MinecraftEffectRuntime::nativeSource);
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                var gear = new ItemStack(Items.DIAMOND_SWORD); gear.setDamageValue(7);
                gear.set(DataComponents.CUSTOM_NAME, Component.literal("Network test rifle"));
                gear.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear("client-rifle", "test:rifle", Map.of("perk", "normal")));
                player.getInventory().setItem(0, gear);
            });
            context.getInput().pressKey(InputConstants.KEY_K);
            context.waitForScreen(EquipmentScreen.class);
            context.waitFor(client -> widget(client, "weapon a Empty") != null);
            context.runOnClient(client -> check(EquipmentTheme.load("chorus_d2:equipment").accent() == 0xffe4cd8d, "D2 resource theme missing"));
            click(context, "weapon a Empty"); click(context, " Network test rifle"); click(context, "Exchange");
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            context.waitFor(client -> widget(client, "weapon a Network test rifle") != null);
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var gear = PlayerEquipment.get(player).item("test:weapon_a");
                check(player.getInventory().getItem(0).isEmpty() && gear.getDamageValue() == 7, "Network exchange lost ownership or item components");
                check(gear.get(ChorusComponents.EQUIPMENT.get()).instance().equals("client-rifle"), "Network exchange changed instance");
            });
            click(context, "Draw"); connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            game.getServer().runOnServer(server -> check(PlayerEquipment.get(connection.getServerPlayer()).snapshot().drawn().equals(Optional.of("test:weapon_a")), "Draw button did not reach server"));
            context.getInput().setCursorPos(1, 1); context.waitTick(); context.takeScreenshot("chorus-equipment-wide");
            context.getInput().resizeWindow(640, 480); context.waitTick();
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                for (var child : screen.children()) if (child instanceof AbstractWidget w)
                    check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height, "Equipment widget outside small screen: " + w.getMessage().getString());
            });
            context.takeScreenshot("chorus-equipment-small");
            click(context, "Unequip"); connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            context.waitFor(client -> widget(client, "weapon a Empty") != null);
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); check(PlayerEquipment.get(player).isEmpty(), "Unequip left physical equipment");
                check(player.getInventory().getItem(0).getDamageValue() == 7 && player.getInventory().getItem(0).has(ChorusComponents.EQUIPMENT.get()), "Unequip did not return real stack");
            });
            click(context, "Close"); context.waitForScreen(null);
        }
    }
    private static AbstractWidget widget(Minecraft client, String label) {
        if (client.gui.screen() == null) return null;
        return client.gui.screen().children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(w -> w.getMessage().getString().equals(label)).findFirst().orElse(null);
    }
    private static void click(ClientGameTestContext context, String label) {
        var point = context.computeOnClient(client -> {
            var w = Objects.requireNonNull(widget(client, label), "Missing widget: " + label); check(w.active, "Disabled widget: " + label);
            var window = client.getWindow();
            return new double[] {(w.getX() + w.getWidth() / 2.0) * window.getScreenWidth() / client.gui.screen().width,
                    (w.getY() + w.getHeight() / 2.0) * window.getScreenHeight() / client.gui.screen().height};
        });
        context.getInput().setCursorPos(point[0], point[1]); context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
    }
    private static void check(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
}
