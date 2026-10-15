package com.imdomestic.chorus.client;

import static com.imdomestic.chorus.network.WeaponChargePayloads.*;
import com.imdomestic.chorus.network.InputTransferPayload;
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
    private static LocalPlayer projectedOwner;
    private static InputTransferGate transfer;
    private static final InputCancellations cancellations = new InputCancellations();
    private static boolean blocked;
    private static int heartbeat;
    private static View view;
    private static void connection(Minecraft client) {
        if (connection.get() != client.getConnection()) {
            connection = new java.lang.ref.WeakReference<>(client.getConnection()); sequence = gesture = revision = 0;
            if (transfer != null) transfer.retire();
            cancellations.clear();
            transfer = null; projectedOwner = null; view = null; blocked = true; heartbeat = 0;
        }
    }
    public static void accept(View incoming) {
        var client = Minecraft.getInstance(); connection(client);
        if (client.player == null || client.level == null || !incoming.player().equals(client.player.getUUID())
                || incoming.entity() != client.player.getId() || incoming.sequence() != sequence
                || incoming.held() && (incoming.gesture() != gesture || transfer == null)
                || transfer != null && (transfer.waiting() || !transfer.matches(client.player, InputTransferClient.dimension(client)))
                || !incoming.dimension().equals(client.level.dimension().identifier().toString()) || incoming.revision() <= revision) return;
        revision = incoming.revision(); view = incoming; projectedOwner = client.player;
        if (gesture != 0 && !incoming.held()) { transfer.retire(); transfer = null; gesture = 0; heartbeat = 0; blocked = true; }
    }
    static void acceptTransfer(InputTransferPayload incoming) {
        var client = Minecraft.getInstance(); connection(client);
        if (incoming.kind() != InputTransferPayload.Kind.CHARGE || !incoming.slot().isEmpty()) return;
        var acknowledgement = InputTransferClient.acknowledgement(incoming);
        if (transfer == null || incoming.gesture() != gesture) {
            cancellations.accept(client.getConnection(), sequence, acknowledgement); return;
        }
        if (!transfer.accept(client.getConnection(), sequence, revision, acknowledgement)) return;
        revision = incoming.revision(); view = null; projectedOwner = null; heartbeat = 0;
    }
    public static Optional<View> view() {
        var client = Minecraft.getInstance(); connection(client);
        return view != null && client.player != null && client.player == projectedOwner && client.level != null && client.player.isAlive() && !client.player.isSpectator()
                && client.gui.screen() == null && client.isWindowActive() && view.player().equals(client.player.getUUID()) && view.entity() == client.player.getId()
                && (transfer == null || !transfer.waiting() && transfer.matches(client.player, InputTransferClient.dimension(client)))
                && (!view.held() || transfer != null && gesture == view.gesture())
                && view.dimension().equals(client.level.dimension().identifier().toString()) ? Optional.of(view) : Optional.empty();
    }
    private static void press(Minecraft client, Consumer<Input> send) {
        gesture = sequence = Math.incrementExact(sequence); transfer = InputTransferClient.start(client, gesture); heartbeat = 0; view = null;
        send.accept(new Input(sequence, gesture, transfer.dimension(), Edge.PRESS, transfer.transferRevision()));
    }
    private static void finish(Edge edge, Consumer<Input> send) {
        long previous = gesture, epoch = transfer.cancellationRevision(); String dimension = transfer.cancellationDimension();
        cancellations.retain(transfer.retireForCancellation(revision), cancel -> send.accept(new Input(
                sequence = Math.incrementExact(sequence), cancel.gesture(), cancel.dimension(), Edge.CANCEL, cancel.transferRevision())));
        transfer = null; gesture = 0; heartbeat = 0; view = null;
        send.accept(new Input(sequence = Math.incrementExact(sequence), previous, dimension, edge, epoch));
    }
    public static void tick(KeyMapping key, Consumer<Input> send, BooleanSupplier supported) {
        var client = Minecraft.getInstance(); connection(client);
        cancellations.tick();
        int clicks = 0; while (key.consumeClick()) clicks++;
        boolean down = InputTransferClient.down(client, key, transfer, blocked), channel = client.getConnection() != null && supported.getAsBoolean();
        if (transfer != null) {
            var decision = InputTransferClient.tick(client, transfer, down, channel);
            if (decision == InputTransferGate.Decision.WAITING) { view = null; heartbeat = 0; return; }
            if (decision == InputTransferGate.Decision.CANCEL) {
                if (channel) finish(Edge.CANCEL, send); else { transfer = null; gesture = 0; }
                view = null; blocked = down || !client.isWindowActive(); return;
            }
        }
        if (!InputTransferClient.eligible(client, channel)) {
            view = null; blocked = down || blocked && !client.isWindowActive(); return;
        }
        if (!down) blocked = false;
        if (blocked) return;
        if (down) {
            if (gesture == 0) press(client, send);
            else if (++heartbeat >= HEARTBEAT_TICKS) { heartbeat = 0; send.accept(new Input(sequence = Math.incrementExact(sequence), gesture, transfer.dimension(), Edge.HOLD, transfer.transferRevision())); }
        } else if (gesture != 0) finish(Edge.RELEASE, send);
        else for (int i = 0; i < clicks; i++) { press(client, send); finish(Edge.RELEASE, send); }
    }
}
