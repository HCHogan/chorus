package com.imdomestic.chorus.network;

import static com.imdomestic.chorus.network.WeaponChargePayloads.*;
import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.effect.weapon.WeaponCharge;
import com.imdomestic.chorus.platform.Services;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** One accepted physical gesture can release once. Heartbeats never start or restart domain charging. */
public final class WeaponChargeNetworkServer {
    public static final int LEASE_TICKS = 40;
    public static final WeaponChargeNetworkServer LIVE = new WeaponChargeNetworkServer((player, view) -> Services.PLATFORM.sendWeaponChargeView(player, view));
    public enum Reply { PROCESSED, DUPLICATE, STALE, REJECTED, FAILED }
    private static final class Session {
        final ServerGamePacketListenerImpl connection;
        ServerPlayer player; ServerLevel level; MinecraftEffectRuntime runtime;
        String life, token = ""; long sequence, revision, gesture, refreshed; boolean held;
        Status status = Status.IDLE; View last;
        Session(ServerPlayer player) { connection = player.connection; owner(player); }
        void owner(ServerPlayer next) { player = next; level = next.level(); life = MinecraftEntityLife.get(next).id(); }
    }
    private final Map<ServerGamePacketListenerImpl, Session> sessions = new IdentityHashMap<>();
    private final BiConsumer<ServerPlayer, View> send;
    public WeaponChargeNetworkServer(BiConsumer<ServerPlayer, View> send) { this.send = Objects.requireNonNull(send); }
    private static void thread(ServerLevel level) { if (!level.getServer().isSameThread()) throw new IllegalStateException("Charge input belongs to server thread"); }
    public Reply request(ServerPlayer player, Input input) {
        thread(player.level()); var s = sessions.computeIfAbsent(player.connection, _ -> new Session(player));
        if (input.sequence() <= s.sequence) return Reply.DUPLICATE;
        s.sequence = input.sequence(); // Rejected or uncertain requests are consumed too.
        try {
            if (s.player != player || s.level != player.level() || !s.life.equals(MinecraftEntityLife.get(player).id())) {
                cancel(s, "input_owner_changed"); s.owner(player); s.runtime = null;
            }
            if (!eligible(s)) { cancel(s, "input_ineligible"); publish(s, true); return Reply.REJECTED; }
            if (!input.dimension().equals(dimension(s))) { publish(s, true); return Reply.REJECTED; }
            var runtime = MinecraftEffectRuntime.installed(s.level).orElse(null);
            if (s.runtime != runtime) replaceRuntime(s, runtime);
            if (runtime == null || runtime.failure().isPresent()) {
                s.held = false; s.status = runtime == null ? Status.UNAVAILABLE : Status.FAILED; publish(s, true); return Reply.REJECTED;
            }
            maintain(s); // Check expiry before a late HOLD or RELEASE can extend/use it.
            if (input.edge() == Edge.PRESS) {
                if (!s.held) {
                    s.gesture = input.gesture(); s.token = UUID.randomUUID().toString(); s.refreshed = s.level.getGameTime();
                    var receipt = runtime.charge(s.player, s.token, WeaponCharge.Edge.PRESS);
                    s.held = receipt.outcome() == WeaponCharge.Outcome.ACCEPTED;
                    s.status = s.held ? Status.ACTIVE : Status.REJECTED;
                    maintain(s); // A start reaction may already have cancelled or replaced the plan.
                }
            } else {
                if (!s.held || input.gesture() != s.gesture) { publish(s, true); return Reply.STALE; }
                if (input.edge() == Edge.HOLD) s.refreshed = s.level.getGameTime();
                else if (input.edge() == Edge.CANCEL) cancel(s, "input_cancelled");
                else {
                    s.held = false; s.status = Status.REJECTED; // Retire before any payment or world effect.
                    var receipt = runtime.charge(s.player, s.token, WeaponCharge.Edge.RELEASE);
                    s.status = receipt.outcome() == WeaponCharge.Outcome.FIRED ? Status.FIRED : Status.REJECTED;
                }
            }
            publish(s, true); return Reply.PROCESSED;
        } catch (RuntimeException error) { return failed(s, error); }
    }
    private static String dimension(Session s) { return s.level.dimension().identifier().toString(); }
    private static boolean eligible(Session s) {
        return s.connection.player == s.player && s.player.level() == s.level && !s.player.isRemoved() && s.player.isAlive()
                && !s.player.isSpectator() && s.life.equals(MinecraftEntityLife.get(s.player).id());
    }
    private static WeaponCharge.Plan plan(Session s) {
        if (s.runtime == null || s.runtime.failure().isPresent() || MinecraftEffectRuntime.installed(s.level).orElse(null) != s.runtime) return null;
        var plan = s.runtime.state().engine().domain().charging().get(s.player.getUUID().toString());
        return plan != null && plan.token().equals(s.token) ? plan : null;
    }
    private static void cancel(Session s, String reason) {
        s.held = false; s.status = Status.CANCELLED;
        if (plan(s) != null) s.runtime.cancelCharge(s.player.getUUID().toString(), s.token, reason);
    }
    private static void maintain(Session s) {
        if (!s.held) return;
        if (s.level.getGameTime() - s.refreshed >= LEASE_TICKS) { cancel(s, "input_expired"); return; }
        s.runtime.prepare();
        if (s.runtime.failure().isPresent()) { s.held = false; s.status = Status.FAILED; }
        else if (plan(s) == null) { s.held = false; s.status = Status.CANCELLED; }
    }
    private static void replaceRuntime(Session s, MinecraftEffectRuntime runtime) {
        cancel(s, "input_runtime_changed"); s.runtime = runtime;
        // Retire the matching saved plan too; do not claim an unrelated command/host plan.
        if (plan(s) != null) runtime.cancelCharge(s.player.getUUID().toString(), s.token, "input_runtime_changed");
        s.status = Status.UNAVAILABLE;
    }
    public void tick(ServerLevel level) {
        thread(level);
        for (var s : List.copyOf(sessions.values())) if (s.level == level) {
            try {
                var runtime = MinecraftEffectRuntime.installed(level).orElse(null);
                if (!eligible(s)) cancel(s, "input_ineligible");
                else if (s.runtime != runtime) replaceRuntime(s, runtime);
                else if (runtime != null && runtime.failure().isPresent()) { s.held = false; s.status = Status.FAILED; }
                else maintain(s);
                publish(s, false);
            } catch (RuntimeException error) { failed(s, error); }
        }
    }
    private Reply failed(Session s, RuntimeException error) {
        s.held = false; s.status = Status.FAILED;
        Constants.LOG.error("Weapon charge input failed; gesture retired without replay", error);
        publish(s, true); return Reply.FAILED;
    }
    private void publish(Session s, boolean force) {
        var plan = s.held && s.status == Status.ACTIVE ? plan(s) : null;
        long observed = plan == null ? 0 : s.runtime.state().engine().timeMicros();
        var view = new View(s.revision + 1, s.sequence, s.gesture, s.player.getUUID(), s.player.getId(), dimension(s), plan != null, s.status,
                plan == null ? Phase.OFF : Phase.valueOf(plan.phase().name()), plan == null ? "" : plan.gear().instance(),
                plan == null ? 0 : plan.startedAt(), plan == null ? 0 : Math.addExact(plan.startedAt(), plan.minimum().micros()), plan == null ? 0 : plan.fullAt(), observed);
        if (!force && s.last != null && new View(s.last.revision(), view.sequence(), view.gesture(), view.player(), view.entity(), view.dimension(), view.held(), view.status(), view.phase(), view.weapon(), view.startedAt(), view.minimumAt(), view.fullAt(), view.observedAt()).equals(s.last)) return;
        s.revision = view.revision(); s.last = view; send.accept(s.player, view);
    }
    public void disconnected(ServerGamePacketListenerImpl connection) {
        var s = sessions.remove(connection); if (s != null) { thread(s.level); cancel(s, "input_disconnected"); }
        MinecraftEffectRuntime.cancelChargeInputs(connection); // Includes self-owned command gestures.
    }
    public void quiesce(MinecraftServer server) {
        for (var s : List.copyOf(sessions.values())) if (s.level.getServer() == server) {
            thread(s.level); sessions.remove(s.connection); cancel(s, "input_server_stopping");
        }
    }
    public void stop(MinecraftServer server) { sessions.values().removeIf(s -> s.level.getServer() == server); }
}
