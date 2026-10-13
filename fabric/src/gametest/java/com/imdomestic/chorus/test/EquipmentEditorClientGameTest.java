package com.imdomestic.chorus.test;

import com.imdomestic.chorus.client.EquipmentScreen;
import com.imdomestic.chorus.client.EquipmentTheme;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;

/** Real widgets, versioned packets, server-owned D2 mod choices, live grant changes and rendered resource icons. */
public final class EquipmentEditorClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> client.options.guiScale().set(2)); context.getInput().resizeWindow(1280, 720);
        try (var game = context.worldBuilder().create()) {
            var connection = game.getConnection(); connection.waitForChunksRender();
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var level = player.level();
                var data = ThreadedSpikeGameTest.json("no_distractions_weapon");
                RadiantGameTest.merge(data, "no_distractions", "weapon_flinch", "weapon_stats", "character_stats"); UnflinchingGameTest.prepare(data);
                data.getAsJsonObject("equipment").addProperty("presentation", "chorus_d2:equipment");
                var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
                var world = new MinecraftWorldActions(level, id -> id.equals(player.getUUID().toString()) ? player : null, _ -> level.damageSources().generic(), (_, _) -> true, _ -> {});
                MinecraftEffectRuntime.install(level, program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world, MinecraftEffectRuntime::nativeSource);
                var equipment = PlayerEquipment.get(player); equipment.grants(player, Set.of("chorus_d2:unlock/unflinching", "chorus_d2:artifact/unflinching_solar"), equipment.revision());
                var chest = new ItemStack(Items.DIAMOND_CHESTPLATE); chest.setDamageValue(9); chest.set(DataComponents.CUSTOM_NAME, Component.literal("Unflinching test chest"));
                chest.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear("ui-chest", "test:unflinching_chest", Map.of("mod_0", "solar_artifact", "mod_1", "solar_artifact", "mod_2", "solar_artifact")));
                player.getInventory().setItem(0, chest); equipment.swap(player, "test:chest", 0, equipment.revision());
            });
            context.getInput().pressKey(InputConstants.KEY_K); context.waitForScreen(EquipmentScreen.class);
            context.waitFor(client -> widget(client, "chest Unflinching test chest") != null);
            click(context, "chest Unflinching test chest"); click(context, "Configure");
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets(); context.waitFor(client -> widget(client, "Apply") != null);
            context.runOnClient(client -> check(EquipmentTheme.load("chorus_d2:equipment").effectIcons().containsKey("chorus_d2:unflinching_solar"), "D2 mod icon mapping missing"));
            click(context, "Solar Unflinching · energy: 3"); click(context, "mod 1 · Solar Unflinching"); click(context, "Solar Unflinching · energy: 3");
            game.getServer().runOnServer(server -> check(PlayerEquipment.get(connection.getServerPlayer()).item("test:chest").get(ChorusComponents.EQUIPMENT.get()).choices().get("mod_0").equals("solar_artifact"), "draft mutated physical equipment before Apply"));
            context.getInput().setCursorPos(1, 1); context.waitTick(); context.takeScreenshot("chorus-equipment-editor-wide");
            click(context, "Apply"); connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            context.waitFor(client -> widget(client, "Apply") != null && !widget(client, "Apply").active);
            game.getServer().runOnServer(server -> {
                var chest = PlayerEquipment.get(connection.getServerPlayer()).item("test:chest"); var gear = chest.get(ChorusComponents.EQUIPMENT.get());
                check(gear.choices().equals(Map.of("mod_0", "solar", "mod_1", "solar", "mod_2", "solar_artifact")), "batch Apply lost a choice");
                check(gear.instance().equals("ui-chest") && chest.getDamageValue() == 9, "Apply rerolled identity or durability");
            });
            click(context, "mod 0 · Solar Unflinching"); click(context, "Arc Unflinching · energy: 3");
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var e = PlayerEquipment.get(player); e.grants(player, Set.of("chorus_d2:unlock/unflinching"), e.revision());
            });
            connection.waitForClientboundPackets();
            context.waitFor(client -> widget(client, "Apply") != null && !widget(client, "Apply").active && widget(client, "Solar Unflinching · energy: 1 · Unlock required") != null);
            context.runOnClient(client -> check(!widget(client, "Solar Unflinching · energy: 1 · Unlock required").active, "revoked artifact remained selectable"));
            click(context, "mod 2 · Solar Unflinching"); click(context, "Solar Unflinching · energy: 3"); click(context, "Apply");
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            context.waitFor(client -> widget(client, "Apply") != null && !widget(client, "Apply").active);
            game.getServer().runOnServer(server -> {
                var e = PlayerEquipment.get(connection.getServerPlayer()); var gear = e.item("test:chest").get(ChorusComponents.EQUIPMENT.get());
                check(gear.choices().equals(Map.of("mod_0", "solar", "mod_1", "solar", "mod_2", "solar")), "authority update retained stale Arc draft or failed to repair revoked mod");
                check(e.inactiveReason().isEmpty(), "valid draft did not reactivate equipment");
            });
            context.getInput().resizeWindow(640, 480); context.waitTick();
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                for (var child : screen.children()) if (child instanceof AbstractWidget w)
                    check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height, "Editor widget outside small window: " + w.getMessage().getString());
            });
            context.getInput().setCursorPos(1, 1); context.waitTick(); context.takeScreenshot("chorus-equipment-editor-small");
            click(context, "Back"); connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            context.waitFor(client -> widget(client, "chest Unflinching test chest") != null); click(context, "Close");
            game.getServer().runOnServer(server -> MinecraftEffectRuntime.installed(connection.getServerPlayer().level()).orElseThrow().close());
        }
    }
    private static AbstractWidget widget(Minecraft client, String label) {
        if (client.gui.screen() == null) return null;
        return client.gui.screen().children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(w -> w.getMessage().getString().equals(label)).findFirst().orElse(null);
    }
    private static void click(ClientGameTestContext context, String label) {
        var point = context.computeOnClient(client -> {
            var w = Objects.requireNonNull(widget(client, label), "Missing widget: " + label); check(w.active, "Disabled widget: " + label); var window = client.getWindow();
            return new double[]{(w.getX() + w.getWidth()/2.0)*window.getScreenWidth()/client.gui.screen().width, (w.getY()+w.getHeight()/2.0)*window.getScreenHeight()/client.gui.screen().height};
        });
        context.getInput().setCursorPos(point[0], point[1]); context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
