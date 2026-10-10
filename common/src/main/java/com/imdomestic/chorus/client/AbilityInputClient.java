package com.imdomestic.chorus.client;

import com.imdomestic.chorus.effect.ability.AbilityInput;
import com.imdomestic.chorus.network.AbilityInputPayload;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.*;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

/** Edge transport only. Conversion timing and whether a release may cast belong to the server. */
public final class AbilityInputClient {
    private AbilityInputClient() {}
    private static java.lang.ref.WeakReference<ClientPacketListener> connection = new java.lang.ref.WeakReference<>(null);
    private static long sequence;
    private static final Map<String, Held> inputs = new HashMap<>();
    private static final class Held { long gesture; String dimension; LocalPlayer player; boolean blocked; }
    private static void press(Held held, String slot, Minecraft client, Consumer<AbilityInputPayload> send) {
        held.gesture = sequence = Math.incrementExact(sequence); held.dimension = client.level.dimension().identifier().toString(); held.player = client.player;
        send.accept(new AbilityInputPayload(sequence, held.gesture, held.dimension, slot, AbilityInput.Edge.PRESS));
    }
    private static void finish(Held held, String slot, AbilityInput.Edge edge, Consumer<AbilityInputPayload> send) {
        long gesture = held.gesture; String dimension = held.dimension; held.gesture = 0; held.player = null;
        sequence = Math.incrementExact(sequence); send.accept(new AbilityInputPayload(sequence, gesture, dimension, slot, edge));
    }
    public static void tick(KeyMapping key, String slot, Consumer<AbilityInputPayload> send, BooleanSupplier supported) {
        var client = Minecraft.getInstance(); var current = client.getConnection();
        if (current != connection.get()) { connection = new java.lang.ref.WeakReference<>(current); sequence = 0; inputs.clear(); }
        var held = inputs.computeIfAbsent(slot, _ -> new Held());
        int clicks = 0; while (key.consumeClick()) clicks++;
        boolean down = key.isDown(); boolean channel = current != null && supported.getAsBoolean();
        boolean eligible = channel && client.player != null && client.level != null && client.player.isAlive() && !client.player.isSpectator()
                && client.gui.screen() == null && client.isWindowActive();
        boolean changed = held.gesture != 0 && (held.player != client.player || client.level == null || !held.dimension.equals(client.level.dimension().identifier().toString()));
        if (!eligible || changed) {
            if (held.gesture != 0) { if (channel) finish(held, slot, AbilityInput.Edge.CANCEL, send); else { held.gesture = 0; held.player = null; } }
            held.blocked = down; return;
        }
        if (!down) held.blocked = false;
        if (held.blocked) return;
        if (down) { if (held.gesture == 0) press(held, slot, client, send); }
        else if (held.gesture != 0) finish(held, slot, AbilityInput.Edge.RELEASE, send);
        else for (int i = 0; i < clicks; i++) { press(held, slot, client, send); finish(held, slot, AbilityInput.Edge.RELEASE, send); }
    }
}
