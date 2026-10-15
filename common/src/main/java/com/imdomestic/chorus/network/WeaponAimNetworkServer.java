package com.imdomestic.chorus.network;

import static com.imdomestic.chorus.network.WeaponAimPayloads.*;
import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.effect.weapon.WeaponAim;
import com.imdomestic.chorus.platform.Services;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.platform.minecraft.MinecraftEntityLife;
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
        long sequence, revision, gesture, refreshed, transferRevision; boolean held; Status status = Status.IDLE; View last;String life;Object transferring;
        Session(ServerPlayer player) { connection = player.connection; this.player = player; level = player.level();life=MinecraftEntityLife.get(player).id(); }
    }
    private final Map<ServerGamePacketListenerImpl, Session> sessions = new IdentityHashMap<>();
    private final BiConsumer<ServerPlayer, View> send;
    private final BiConsumer<ServerPlayer,InputTransferPayload> sendTransfer;
    public WeaponAimNetworkServer(BiConsumer<ServerPlayer, View> send) { this(send,(player,payload)->Services.PLATFORM.sendInputTransfer(player,payload)); }
    public WeaponAimNetworkServer(BiConsumer<ServerPlayer, View> send,BiConsumer<ServerPlayer,InputTransferPayload> sendTransfer) { this.send = Objects.requireNonNull(send);this.sendTransfer=Objects.requireNonNull(sendTransfer); }
    private static void thread(ServerLevel level) { if (!level.getServer().isSameThread()) throw new IllegalStateException("Aim input belongs to server thread"); }
    public Reply request(ServerPlayer player, Input input) {
        thread(player.level()); var s = sessions.computeIfAbsent(player.connection, _ -> new Session(player));
        if(s.transferring!=null)return Reply.REJECTED;
        if (input.sequence() <= s.sequence) return Reply.DUPLICATE;
        s.sequence = input.sequence(); // Even rejected requests cannot later become valid through replay.
        try {
            if (s.player != player || s.level != player.level() || !s.life.equals(MinecraftEntityLife.get(player).id())) {
                cancel(s, "input_owner_changed"); s.player = player; s.level = player.level(); s.runtime = null;s.life=MinecraftEntityLife.get(player).id();
            }
            if (!eligible(s)) { cancel(s, "input_ineligible"); publish(s, true); return Reply.REJECTED; }
            if (!input.dimension().equals(dimension(s))) { publish(s, true); return Reply.REJECTED; }
            var runtime = MinecraftEffectRuntime.installed(s.level).orElse(null);
            if (runtime != s.runtime) replaceRuntime(s, runtime);
            if (runtime == null || runtime.failure().isPresent()) { s.held = false; s.status = runtime == null ? Status.UNAVAILABLE : Status.FAILED; publish(s, true); return Reply.REJECTED; }
            if(s.held&&s.level.getGameTime()-s.refreshed>=LEASE_TICKS)cancel(s,"input_expired");
            if(input.transferRevision()!=(input.edge()==Edge.PRESS&&!s.held?0:s.transferRevision)){publish(s,true);return Reply.REJECTED;}
            if (input.edge() == Edge.PRESS) {
                // A second PRESS never replaces the identity or starting time of an existing gesture.
                if (!s.held) { s.transferRevision=0;s.gesture = input.gesture(); s.held = true; s.refreshed = s.level.getGameTime(); }
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
    private static boolean eligible(Session s) { return s.connection.player==s.player&&s.player.level() == s.level && !s.player.isRemoved() && s.player.isAlive() && !s.player.isSpectator()&&s.life.equals(MinecraftEntityLife.get(s.player).id()); }
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
        for (var s : List.copyOf(sessions.values())) if (s.level == level&&s.transferring==null) {
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
    /** Settle this connection's existing lease before the host captures any transfer proposal. Never renews it. */
    public void settleTransfer(ServerPlayer player) {
        thread(player.level());var s=sessions.get(player.connection);if(s==null)return;
        if(s.transferring!=null)throw new IllegalStateException("Aim transfer is already prepared");
        var runtime=MinecraftEffectRuntime.installed(s.level).orElse(null);
        if(!eligible(s))cancel(s,"input_ineligible");
        else if(s.runtime!=runtime)replaceRuntime(s,runtime);
        else if(runtime!=null&&runtime.failure().isPresent()){s.held=false;s.status=Status.FAILED;}
        else if(s.held&&s.level.getGameTime()-s.refreshed>=LEASE_TICKS)cancel(s,"input_expired");
        else if(s.held)maintain(s);
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
                plan == null ? Phase.OFF : Phase.valueOf(plan.phase().name()), plan == null ? "" : plan.gear().instance(), plan == null ? 0 : plan.startTime(), plan == null ? 0 : plan.dueTime());
        if (!force && s.last != null && new View(s.last.revision(), view.sequence(), view.gesture(), view.player(), view.dimension(), view.held(), view.status(), view.phase(), view.weapon(), view.startedAt(), view.dueAt()).equals(s.last)) return;
        s.revision = view.revision(); s.last = view; send.accept(s.player, view);
    }
    /** Freeze and retarget the same connection gesture; no rule or world work is performed here. */
    public InputTransfer prepareTransfer(ServerPlayer player,MinecraftEffectRuntime source,MinecraftEffectRuntime target) {
        thread(player.level());var s=sessions.get(player.connection);if(s==null||!s.held)return InputTransfer.NONE;
        if(s.transferring!=null||s.player!=player||s.runtime!=source||s.level!=source.level()||!eligible(s)
                ||source==target||target.level().getServer()!=player.level().getServer()
                ||MinecraftEffectRuntime.installed(target.level()).orElse(null)!=target)throw new IllegalStateException("Aim gesture cannot transfer");
        long elapsed=Math.subtractExact(s.level.getGameTime(),s.refreshed);
        if(elapsed<0)throw new IllegalStateException("Aim lease clock moved backwards");
        long refreshed=Math.subtractExact(target.level().getGameTime(),elapsed),revision=s.held?Math.incrementExact(s.revision):s.revision;
        Math.incrementExact(revision); // Reserve both the confirmation and its following view before freezing.
        long previousRevision=s.transferRevision;var marker=new Object();s.transferring=marker;String from=dimension(s);boolean held=s.held;
        return new InputTransfer(){boolean committed,finished,aborted;
            private void current(){if(finished||s.transferring!=marker||sessions.get(s.connection)!=s)throw new IllegalStateException("Stale aim transfer");}
            public void commit(){current();if(committed||s.connection.player!=player||player.level()!=target.level()||!s.life.equals(MinecraftEntityLife.get(player).id()))throw new IllegalStateException("Aim transfer lost native owner");s.level=target.level();s.runtime=target;s.refreshed=refreshed;s.revision=revision;s.transferRevision=revision;committed=true;}
            public void publish(){current();if(!committed)throw new IllegalStateException("Aim transfer not committed");s.transferring=null;finished=true;
                if(held)sendTransfer.accept(player,new InputTransferPayload(InputTransferPayload.Kind.AIM,"",player.getUUID(),player.getId(),from,dimension(s),s.sequence,s.gesture,revision,previousRevision));
                WeaponAimNetworkServer.this.publish(s,true);}
            public void fail(){if(aborted)return;if(finished&&s.transferring!=null&&s.transferring!=marker)return;s.held=false;s.status=Status.FAILED;if(s.transferring==marker)s.transferring=null;finished=true;}
            public void abort(){current();if(committed)throw new IllegalStateException("Committed aim transfer cannot abort");s.transferring=null;finished=true;aborted=true;}
        };
    }
    /** Current epoch of this connection gesture; ordinary projections do not advance it. */
    public long transferRevision(ServerPlayer player){thread(player.level());var s=sessions.get(player.connection);return s==null||s.player!=player?0:s.transferRevision;}
    public void disconnected(ServerGamePacketListenerImpl connection) {
        var s = sessions.remove(connection); if (s != null) { thread(s.level);if(s.transferring!=null){s.held=false;s.status=Status.IDLE;}else cancel(s, "input_disconnected"); }
    }
    /** Run before pinning a clean-stop checkpoint, while rule reactions can still complete normally. */
    public void quiesce(MinecraftServer server) {
        for (var s : List.copyOf(sessions.values())) if (s.level.getServer() == server) {
            thread(s.level); sessions.remove(s.connection); cancel(s, "input_server_stopping");
        }
    }
    public void stop(MinecraftServer server) { sessions.values().removeIf(s -> s.level.getServer() == server); }
}
