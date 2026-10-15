package com.imdomestic.chorus.client;

import com.imdomestic.chorus.effect.ability.AbilityInput;
import com.imdomestic.chorus.network.AbilityInputPayload;
import com.imdomestic.chorus.network.InputTransferPayload;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.*;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Edge transport only. Conversion timing and whether a release may cast belong to the server. */
public final class AbilityInputClient {
    private AbilityInputClient() {}
    private static java.lang.ref.WeakReference<ClientPacketListener> connection = new java.lang.ref.WeakReference<>(null);
    private static long sequence;
    private static final Map<String, Held> inputs = new HashMap<>();
    private static final class Held {
        long gesture, revision; InputTransferGate transfer; boolean blocked;
        final InputCancellations cancellations = new InputCancellations();
        final InputLease lease = new InputLease();
    }
    private static void connection(Minecraft client) {
        var current = client.getConnection();
        if (current != connection.get()) {
            for (var held : inputs.values()) if (held.transfer != null) held.transfer.retire();
            connection = new java.lang.ref.WeakReference<>(current); sequence = 0; inputs.clear();
        }
    }
    static void acceptTransfer(InputTransferPayload incoming) {
        var client = Minecraft.getInstance(); connection(client);
        if (incoming.kind() != InputTransferPayload.Kind.ABILITY) return;
        var held = inputs.get(incoming.slot());
        if (held == null) return;
        var acknowledgement = InputTransferClient.acknowledgement(incoming);
        if (held.transfer == null || incoming.gesture() != held.gesture) {
            held.cancellations.accept(client.getConnection(), sequence, acknowledgement); return;
        }
        if (!held.transfer.accept(client.getConnection(), sequence, held.revision, acknowledgement)) return;
        held.revision = incoming.revision();
    }
    private static void press(Held held, String slot, Minecraft client, Consumer<AbilityInputPayload> send) {
        held.gesture = sequence = Math.incrementExact(sequence); held.transfer = InputTransferClient.start(client, held.gesture); held.lease.reset();
        send.accept(new AbilityInputPayload(sequence, held.gesture, held.transfer.dimension(), slot, AbilityInput.Edge.PRESS, held.transfer.transferRevision()));
    }
    private static void finish(Held held, String slot, AbilityInput.Edge edge, Consumer<AbilityInputPayload> send) {
        long gesture = held.gesture, epoch = held.transfer.cancellationRevision(); String dimension = held.transfer.cancellationDimension();
        held.cancellations.retain(held.transfer.retireForCancellation(held.revision), cancel -> send.accept(new AbilityInputPayload(
                sequence = Math.incrementExact(sequence), cancel.gesture(), cancel.dimension(), slot, AbilityInput.Edge.CANCEL, cancel.transferRevision())));
        held.transfer = null; held.gesture = 0; held.lease.reset();
        sequence = Math.incrementExact(sequence); send.accept(new AbilityInputPayload(sequence, gesture, dimension, slot, edge, epoch));
    }
    public static void tick(KeyMapping key, String slot, Consumer<AbilityInputPayload> send, BooleanSupplier supported) {
        var client = Minecraft.getInstance(); connection(client); var current = client.getConnection();
        var held = inputs.computeIfAbsent(slot, _ -> new Held());
        held.cancellations.tick();
        int clicks = 0; while (key.consumeClick()) clicks++;
        boolean down = InputTransferClient.down(client, key, held.transfer, held.blocked); boolean channel = current != null && supported.getAsBoolean();
        if (held.transfer != null) {
            // Retire before evaluating a late acknowledgement, so it cannot rebind an already expired physical gesture.
            if (!held.lease.advance()) held.transfer.retire();
            var decision = InputTransferClient.tick(client, held.transfer, down, channel);
            if (decision == InputTransferGate.Decision.WAITING) return;
            if (decision == InputTransferGate.Decision.CANCEL) {
                if (channel) finish(held, slot, AbilityInput.Edge.CANCEL, send); else { held.transfer = null; held.gesture = 0; held.lease.reset(); }
                held.blocked = down || !client.isWindowActive(); return;
            }
        }
        if (!InputTransferClient.eligible(client, channel)) {
            held.blocked = down || held.blocked && !client.isWindowActive(); return;
        }
        if (!down) held.blocked = false;
        if (held.blocked) return;
        if (down) {
            if (held.gesture == 0) press(held, slot, client, send);
            else if (held.lease.heartbeatDue()) {
                held.lease.reset();
                send.accept(new AbilityInputPayload(sequence = Math.incrementExact(sequence), held.gesture, held.transfer.dimension(), slot,
                        AbilityInputPayload.Edge.HOLD, held.transfer.transferRevision()));
            }
        }
        else if (held.gesture != 0) finish(held, slot, AbilityInput.Edge.RELEASE, send);
        else for (int i = 0; i < clicks; i++) { press(held, slot, client, send); finish(held, slot, AbilityInput.Edge.RELEASE, send); }
    }
}
