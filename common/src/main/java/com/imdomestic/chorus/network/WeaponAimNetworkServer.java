package com.imdomestic.chorus.network;

import static com.imdomestic.chorus.network.WeaponAimPayloads.*;
import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.effect.weapon.WeaponAim;
import com.imdomestic.chorus.platform.Services;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Connection-local held intent. Never checkpointed or retried after an uncertain world result. */
public final class WeaponAimNetworkServer {
    public static final int LEASE_TICKS = 40;
    public static final WeaponAimNetworkServer LIVE = new WeaponAimNetworkServer((player, view) -> Services.PLATFORM.sendWeaponAimView(player, view));
    public enum Reply { PROCESSED, DUPLICATE, STALE, REJECTED, FAILED }
    private static final class Session {
        final ServerGamePacketListenerImpl connection;
        ServerPlayer player; ServerLevel level; MinecraftEffectRuntime runtime;
        long sequence, revision, gesture, refreshed; boolean held; Status status = Status.IDLE; View last;
        Session(ServerPlayer player) { connection = player.connection; this.player = player; level = player.level(); }
    }
    private final Map<ServerGamePacketListenerImpl, Session> sessions = new IdentityHashMap<>();
    private final BiConsumer<ServerPlayer, View> send;
    public WeaponAimNetworkServer(BiConsumer<ServerPlayer, View> send) { this.send = Objects.requireNonNull(send); }
    private static void thread(ServerLevel level) { if (!level.getServer().isSameThread()) throw new IllegalStateException("Aim input belongs to server thread"); }
    public Reply request(ServerPlayer player, Input input) {
        thread(player.level()); var s = sessions.computeIfAbsent(player.connection, _ -> new Session(player));
        if (input.sequence() <= s.sequence) return Reply.DUPLICATE;
        s.sequence = input.sequence(); // Even rejected requests cannot later become valid through replay.
        try {
            if (s.player != player || s.level != player.level()) {
                cancel(s, "input_owner_changed"); s.player = player; s.level = player.level(); s.runtime = null;
            }
            if (!eligible(s)) { cancel(s, "input_ineligible"); publish(s, true); return Reply.REJECTED; }
            if (!input.dimension().equals(dimension(s))) { publish(s, true); return Reply.REJECTED; }
            var runtime = MinecraftEffectRuntime.installed(s.level).orElse(null);
            if (runtime != s.runtime) replaceRuntime(s, runtime);
            if (runtime == null || runtime.failure().isPresent()) { s.held = false; s.status = runtime == null ? Status.UNAVAILABLE : Status.FAILED; publish(s, true); return Reply.REJECTED; }
            if (input.edge() == Edge.PRESS) {
                // A second PRESS never replaces the identity or starting time of an existing gesture.
                if (!s.held) { s.gesture = input.gesture(); s.held = true; s.refreshed = s.level.getGameTime(); }
                maintain(s);
            } else {
                if (!s.held || input.gesture() != s.gesture) { publish(s, true); return Reply.STALE; }
                if (input.edge() == Edge.HOLD) { s.refreshed = s.level.getGameTime(); maintain(s); }
                else {
                    s.held = false; s.status = Status.IDLE; // Retire before reactions, including ones with unknown results.
                    if (input.edge() == Edge.CANCEL) runtime.cancelAim(s.player.getUUID().toString(), "input_cancelled");
                    else runtime.aim(s.player, false);
                }
            }
            publish(s, true); return Reply.PROCESSED;
        } catch (RuntimeException error) { return failed(s, error); }
    }
    private static String dimension(Session s) { return s.level.dimension().identifier().toString(); }
    private static boolean eligible(Session s) { return s.player.level() == s.level && !s.player.isRemoved() && s.player.isAlive() && !s.player.isSpectator(); }
    private static void cancel(Session s, String reason) {
        s.held = false; s.status = Status.IDLE;
        if (s.runtime != null && MinecraftEffectRuntime.installed(s.level).orElse(null) == s.runtime && s.runtime.failure().isEmpty())
            s.runtime.cancelAim(s.player.getUUID().toString(), reason);
    }
    private static void maintain(Session s) {
        if (!s.held) return;
        // CONTINUE can differ from START. Retire the current aim before considering a fresh accepted entry.
        var decision = s.runtime.aimContinuation(s.player);
        if (decision.isPresent() && !decision.orElseThrow().allowed()) {
            s.runtime.cancelAim(s.player.getUUID().toString(), "input_restricted"); s.status = Status.RESTRICTED; return;
        }
        var receipt = s.runtime.aim(s.player, true);
        s.status = switch (receipt.outcome()) {
            case ACCEPTED, UNCHANGED -> Status.ACTIVE;
            case BUSY -> Status.BUSY;
            case RESTRICTED -> Status.RESTRICTED;
            case EMPTY_HANDS -> s.runtime.state().engine().domain().handling().containsKey(s.player.getUUID().toString()) ? Status.BUSY : Status.UNAVAILABLE;
            case NOT_CONFIGURED -> Status.UNAVAILABLE;
        };
        if (s.status == Status.UNAVAILABLE) s.held = false;
    }
    private static void replaceRuntime(Session s, MinecraftEffectRuntime runtime) {
        boolean held = s.held; cancel(s, "input_runtime_changed"); s.runtime = runtime;
        // A restored domain snapshot can contain the former aim, but the old live gesture is not transferable.
        if (held && runtime != null && runtime.failure().isEmpty()) runtime.cancelAim(s.player.getUUID().toString(), "input_runtime_changed");
        s.status = Status.UNAVAILABLE;
    }
    public void tick(ServerLevel level) {
        thread(level);
        for (var s : List.copyOf(sessions.values())) if (s.level == level) {
            try {
                var runtime = MinecraftEffectRuntime.installed(level).orElse(null);
                if (!eligible(s) || s.connection.player != s.player) cancel(s, "input_ineligible");
                else if (s.runtime != runtime) replaceRuntime(s, runtime);
                else if (runtime != null && runtime.failure().isPresent()) { s.held = false; s.status = Status.FAILED; }
                else if (s.held && level.getGameTime() - s.refreshed >= LEASE_TICKS) cancel(s, "input_expired");
                else if (s.held) maintain(s);
                publish(s, false);
            } catch (RuntimeException error) { failed(s, error); }
        }
    }
    private Reply failed(Session s, RuntimeException error) {
        s.held = false; s.status = Status.FAILED;
        Constants.LOG.error("Weapon aim input failed; intent retired without replay", error);
        publish(s, true); return Reply.FAILED;
    }
    private void publish(Session s, boolean force) {
        WeaponAim.Plan plan = s.runtime == null || s.status == Status.FAILED || MinecraftEffectRuntime.installed(s.level).orElse(null) != s.runtime
                ? null : s.runtime.state().engine().domain().aiming().get(s.player.getUUID().toString());
        var view = new View(s.revision + 1, s.sequence, s.gesture, s.player.getUUID(), dimension(s), s.held, s.status,
                plan == null ? Phase.OFF : Phase.valueOf(plan.phase().name()), plan == null ? "" : plan.gear().instance(), plan == null ? 0 : plan.startedAt(), plan == null ? 0 : plan.dueAt());
        if (!force && s.last != null && new View(s.last.revision(), view.sequence(), view.gesture(), view.player(), view.dimension(), view.held(), view.status(), view.phase(), view.weapon(), view.startedAt(), view.dueAt()).equals(s.last)) return;
        s.revision = view.revision(); s.last = view; send.accept(s.player, view);
    }
    public void disconnected(ServerGamePacketListenerImpl connection) {
        var s = sessions.remove(connection); if (s != null) { thread(s.level); cancel(s, "input_disconnected"); }
    }
    /** Run before pinning a clean-stop checkpoint, while rule reactions can still complete normally. */
    public void quiesce(MinecraftServer server) {
        for (var s : List.copyOf(sessions.values())) if (s.level.getServer() == server) {
            thread(s.level); sessions.remove(s.connection); cancel(s, "input_server_stopping");
        }
    }
    public void stop(MinecraftServer server) { sessions.values().removeIf(s -> s.level.getServer() == server); }
}
