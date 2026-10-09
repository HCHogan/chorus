package com.imdomestic.chorus.network;

import static com.imdomestic.chorus.network.EquipmentPayloads.*;
import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.platform.Services;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Authenticates against the connection's player and the exact view they were shown. Server-thread only. */
public final class EquipmentNetworkServer {
    public static final EquipmentNetworkServer LIVE = new EquipmentNetworkServer((player, view) -> Services.PLATFORM.sendEquipmentView(player, view));
    private static final class Session {
        final ServerPlayer player; final ServerLevel level; final UUID screen, token = UUID.randomUUID();
        long sequence; Content content; MinecraftEffectRuntime runtime;
        Session(ServerPlayer player, UUID screen) { this.player = player; this.level = player.level(); this.screen = screen; }
    }
    private record Captured(Content content, MinecraftEffectRuntime runtime) {}
    private final Map<ServerPlayer, Session> sessions = new IdentityHashMap<>();
    private final BiConsumer<ServerPlayer, View> send;
    public EquipmentNetworkServer(BiConsumer<ServerPlayer, View> send) { this.send = Objects.requireNonNull(send); }
    private static void thread(ServerPlayer player) { if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Equipment network handler belongs to server thread"); }
    public void visit(ServerPlayer player, Visit visit) {
        thread(player);
        if (!visit.open()) { var old = sessions.get(player); if (old != null && old.screen.equals(visit.screen())) sessions.remove(player); return; }
        if (player.isRemoved()) return;
        var session = new Session(player, visit.screen()); sessions.put(player, session); publish(session, capture(player), Reply.UPDATED);
    }
    public void request(ServerPlayer player, Request request) {
        thread(player); if (player.isRemoved()) return;
        var session = sessions.get(player);
        if (session == null || session.level != player.level()) { visit(player, new Visit(request.screen(), true)); return; }
        if (!session.screen.equals(request.screen()) || !session.token.equals(request.session())) return;
        var current = capture(player);
        if (request.view() != session.sequence || session.runtime != current.runtime || !current.content.same(session.content)) {
            publish(session, current, Reply.STALE); return;
        }
        if (current.content.status() == Status.DEAD || current.content.status() == Status.BUSY) { publish(session, current, Reply.REJECTED); return; }
        Reply reply = Reply.APPLIED;
        try {
            var equipment = PlayerEquipment.get(player); long revision = current.content.equipment().revision();
            switch (request.operation()) {
                case SWAP -> equipment.swap(player, request.slot(), request.inventory(), revision);
                case DRAW -> equipment.draw(player, Optional.of(request.slot()), revision);
                case STOW -> equipment.draw(player, Optional.empty(), revision);
                case MOVE -> equipment.move(player, request.slot(), request.destination(), revision);
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            reply = MinecraftEffectRuntime.installed(player.level()).filter(runtime -> runtime.failure().isPresent()).isPresent() ? Reply.FAILED : Reply.REJECTED;
        } catch (RuntimeException failure) {
            Constants.LOG.error("Equipment request failed; sending authoritative state without replay", failure); reply = Reply.FAILED;
        }
        // Advance even for rejected/no-op requests, so replaying one view never repeats an operation.
        publish(session, capture(player), reply);
    }
    public void tick(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Equipment sync belongs to server thread");
        for (var session : List.copyOf(sessions.values())) {
            if (session.level != level) continue;
            if (session.player.isRemoved() || session.player.level() != session.level) { sessions.remove(session.player); continue; }
            var current = capture(session.player);
            if (session.runtime != current.runtime || !current.content.same(session.content)) publish(session, current, Reply.UPDATED);
        }
    }
    public void stop(MinecraftServer server) { sessions.keySet().removeIf(player -> player.level().getServer() == server); }
    private void publish(Session session, Captured captured, Reply reply) {
        session.content = captured.content; session.runtime = captured.runtime; session.sequence = Math.incrementExact(session.sequence);
        send.accept(session.player, new View(session.screen, session.token, session.sequence, session.player.getUUID(), session.level.dimension().identifier().toString(), reply, captured.content));
    }
    private static Captured capture(ServerPlayer player) {
        var equipment = PlayerEquipment.get(player); var runtime = MinecraftEffectRuntime.installed(player.level()).orElse(null);
        if (runtime != null && runtime.failure().isEmpty()) { runtime.trackEquipment(player); runtime.prepare(); }
        var slots = new TreeMap<String, Slot>(); String presentation = "chorus:equipment", version = "";
        if (runtime != null) {
            var schema = runtime.program().equipment().schema();
            schema.slots().forEach(slot -> slots.put(slot.id(), new Slot(slot.id(), slot.weapon())));
            presentation = schema.presentation().orElse(presentation); version = runtime.program().program().version();
        }
        var stored = equipment.snapshot(); stored.items().keySet().forEach(slot -> slots.putIfAbsent(slot, new Slot(slot, false)));
        Status status = !player.isAlive() ? Status.DEAD : player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty() ? Status.BUSY
                : runtime == null ? Status.NO_RULESET : runtime.failure().isPresent() ? Status.RUNTIME_FAILED : equipment.inactiveReason().isPresent() ? Status.INVALID_EQUIPMENT : Status.READY;
        return new Captured(new Content(stored, List.copyOf(slots.values()), player.getInventory().getNonEquipmentItems(), status, presentation, version), runtime);
    }
}
