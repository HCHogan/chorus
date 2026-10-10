package com.imdomestic.chorus.network;

import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Consumes the input sequence before effects, including rejection and unknown world outcomes. */
public final class ProjectileCatchNetworkServer {
    public static final ProjectileCatchNetworkServer LIVE = new ProjectileCatchNetworkServer();
    public enum Reply { CAUGHT, NO_PROJECTILE, REJECTED, DUPLICATE, FAILED }
    private final Map<ServerGamePacketListenerImpl, Long> sequences = new IdentityHashMap<>();
    public Reply request(ServerPlayer player, ProjectileCatchPayload input) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Catch input belongs to server thread");
        if (input.sequence() <= sequences.getOrDefault(player.connection, 0L)) return Reply.DUPLICATE;
        sequences.put(player.connection, input.sequence());
        if (player.isRemoved() || !player.isAlive() || player.isSpectator() || !player.level().dimension().identifier().toString().equals(input.dimension())) return Reply.REJECTED;
        var runtime = MinecraftEffectRuntime.installed(player.level()).orElse(null);
        if (runtime == null) return Reply.REJECTED;
        try {
            boolean caught = runtime.catchProjectile(player).isPresent();
            return runtime.failure().isPresent() ? Reply.FAILED : caught ? Reply.CAUGHT : Reply.NO_PROJECTILE;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return runtime.failure().isPresent() ? Reply.FAILED : Reply.REJECTED;
        } catch (RuntimeException failure) {
            Constants.LOG.error("Catch input failed; consumed inputs will not replay", failure); return Reply.FAILED;
        }
    }
    public void disconnected(ServerGamePacketListenerImpl connection) { sequences.remove(connection); }
    public void stop(MinecraftServer server) { sequences.keySet().removeIf(connection -> connection.player.level().getServer() == server); }
}
