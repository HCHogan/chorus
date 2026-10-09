package com.imdomestic.chorus.client;

import com.imdomestic.chorus.network.EquipmentPayloads;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class EquipmentClient {
    private EquipmentClient() {}
    private static Consumer<CustomPacketPayload> sender;
    private static BooleanSupplier available;
    public static void init(Consumer<CustomPacketPayload> send, BooleanSupplier supports) { sender = send; available = supports; }
    public static void send(CustomPacketPayload payload) { if (Minecraft.getInstance().getConnection() != null && available != null && available.getAsBoolean()) sender.accept(payload); }
    public static void open() {
        var client = Minecraft.getInstance(); if (client.player == null || client.gui.screen() != null) return;
        if (available == null || !available.getAsBoolean()) { client.gui.hud.setOverlayMessage(Component.translatable("equipment.chorus.unavailable"), true); return; }
        client.gui.setScreen(new EquipmentScreen());
    }
    public static void accept(EquipmentPayloads.View view) {
        var client = Minecraft.getInstance();
        if (client.gui.screen() instanceof EquipmentScreen screen && client.player != null && client.level != null
                && client.player.getUUID().equals(view.player()) && client.level.dimension().identifier().toString().equals(view.dimension())) screen.accept(view);
    }
}
