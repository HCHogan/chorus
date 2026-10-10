package com.imdomestic.chorus.network;

import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.effect.ability.AbilityInput;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** One monotonic edge stream per connection. Consumed releases cannot replay an uncertain cast. */
public final class AbilityInputNetworkServer {
    public static final AbilityInputNetworkServer LIVE = new AbilityInputNetworkServer();
    public enum Reply { PROCESSED, DUPLICATE, REJECTED, FAILED }
    public record Result(Reply reply, Optional<AbilityInput.Receipt> input) {}
    private final Map<ServerGamePacketListenerImpl, Long> sequences = new IdentityHashMap<>();
    public Result request(ServerPlayer player, AbilityInputPayload input) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Ability input belongs to server thread");
        if (input.sequence() <= sequences.getOrDefault(player.connection, 0L)) return new Result(Reply.DUPLICATE, Optional.empty());
        sequences.put(player.connection, input.sequence());
        if (player.isRemoved() || !player.isAlive() || player.isSpectator() || !player.level().dimension().identifier().toString().equals(input.dimension())) return new Result(Reply.REJECTED, Optional.empty());
        var runtime = MinecraftEffectRuntime.installed(player.level()).orElse(null);
        if (runtime == null) return new Result(Reply.REJECTED, Optional.empty());
        try {
            var receipt = runtime.abilityInput(player, input.slot(), input.edge(), input.gesture());
            return new Result(runtime.failure().isPresent() ? Reply.FAILED : Reply.PROCESSED, Optional.of(receipt));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return new Result(runtime.failure().isPresent() ? Reply.FAILED : Reply.REJECTED, Optional.empty());
        } catch (RuntimeException failure) {
            Constants.LOG.error("Ability input failed; consumed input will not replay", failure); return new Result(Reply.FAILED, Optional.empty());
        }
    }
    public void disconnected(ServerGamePacketListenerImpl connection) { sequences.remove(connection); MinecraftEffectRuntime.cancelAbilityInputs(connection); }
    public void stop(MinecraftServer server) { sequences.keySet().removeIf(c -> c.player.level().getServer() == server); }
}
