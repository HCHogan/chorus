package com.imdomestic.chorus.client;

import static com.imdomestic.chorus.network.WeaponChargePayloads.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.*;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

/** Client input and read-only authoritative projection; never predicts readiness or perk qualification. */
public final class WeaponChargeClient {
    private WeaponChargeClient() {}
    public static final int HEARTBEAT_TICKS = 10;
    private static java.lang.ref.WeakReference<ClientPacketListener> connection = new java.lang.ref.WeakReference<>(null);
    private static long sequence, gesture, revision;
    private static String dimension;
    private static LocalPlayer owner, projectedOwner;
    private static boolean blocked;
    private static int heartbeat;
    private static View view;
    private static void connection(Minecraft client) {
        if (connection.get() != client.getConnection()) {
            connection = new java.lang.ref.WeakReference<>(client.getConnection()); sequence = gesture = revision = 0;
            owner = null; projectedOwner = null; view = null; blocked = true; heartbeat = 0;
        }
    }
    public static void accept(View incoming) {
        var client = Minecraft.getInstance(); connection(client);
        if (client.player == null || client.level == null || !incoming.player().equals(client.player.getUUID())
                || incoming.entity() != client.player.getId() || incoming.sequence() != sequence
                || incoming.held() && (incoming.gesture() != gesture || owner != client.player)
                || !incoming.dimension().equals(client.level.dimension().identifier().toString()) || incoming.revision() <= revision) return;
        revision = incoming.revision(); view = incoming; projectedOwner = client.player;
        if (gesture != 0 && !incoming.held()) { gesture = 0; owner = null; heartbeat = 0; blocked = true; }
    }
    public static Optional<View> view() {
        var client = Minecraft.getInstance(); connection(client);
        return view != null && client.player != null && client.player == projectedOwner && client.level != null && client.player.isAlive() && !client.player.isSpectator()
                && client.gui.screen() == null && client.isWindowActive() && view.player().equals(client.player.getUUID()) && view.entity() == client.player.getId()
                && (!view.held() || owner == client.player && gesture == view.gesture())
                && view.dimension().equals(client.level.dimension().identifier().toString()) ? Optional.of(view) : Optional.empty();
    }
    private static void press(Minecraft client, Consumer<Input> send) {
        gesture = sequence = Math.incrementExact(sequence); dimension = client.level.dimension().identifier().toString(); owner = client.player; heartbeat = 0; view = null;
        send.accept(new Input(sequence, gesture, dimension, Edge.PRESS));
    }
    private static void finish(Edge edge, Consumer<Input> send) {
        long previous = gesture; gesture = 0; owner = null; heartbeat = 0; view = null;
        send.accept(new Input(sequence = Math.incrementExact(sequence), previous, dimension, edge));
    }
    public static void tick(KeyMapping key, Consumer<Input> send, BooleanSupplier supported) {
        var client = Minecraft.getInstance(); connection(client);
        int clicks = 0; while (key.consumeClick()) clicks++;
        boolean down = key.isDown(), channel = client.getConnection() != null && supported.getAsBoolean();
        boolean eligible = channel && client.player != null && client.level != null && client.player.isAlive() && !client.player.isSpectator()
                && client.gui.screen() == null && client.isWindowActive();
        boolean changed = gesture != 0 && (owner != client.player || client.level == null || !dimension.equals(client.level.dimension().identifier().toString()));
        if (!eligible || changed) {
            if (gesture != 0) { if (channel) finish(Edge.CANCEL, send); else { gesture = 0; owner = null; } }
            view = null; blocked = down; return;
        }
        if (!down) blocked = false;
        if (blocked) return;
        if (down) {
            if (gesture == 0) press(client, send);
            else if (++heartbeat >= HEARTBEAT_TICKS) { heartbeat = 0; send.accept(new Input(sequence = Math.incrementExact(sequence), gesture, dimension, Edge.HOLD)); }
        } else if (gesture != 0) finish(Edge.RELEASE, send);
        else for (int i = 0; i < clicks; i++) { press(client, send); finish(Edge.RELEASE, send); }
    }
}
