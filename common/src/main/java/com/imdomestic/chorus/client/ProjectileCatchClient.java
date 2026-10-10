package com.imdomestic.chorus.client;

import com.imdomestic.chorus.network.ProjectileCatchPayload;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

public final class ProjectileCatchClient {
    private ProjectileCatchClient() {}
    private static java.lang.ref.WeakReference<ClientPacketListener> connection = new java.lang.ref.WeakReference<>(null);
    private static long sequence;
    public static void press(Consumer<ProjectileCatchPayload> send, BooleanSupplier supported) {
        var client = Minecraft.getInstance(); var current = client.getConnection();
        if (current != connection.get()) { connection = new java.lang.ref.WeakReference<>(current); sequence = 0; }
        if (current == null || client.player == null || client.level == null || client.gui.screen() != null || !supported.getAsBoolean()) return;
        sequence = Math.incrementExact(sequence);
        send.accept(new ProjectileCatchPayload(sequence, client.level.dimension().identifier().toString()));
    }
}
