package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.motion.GroundContact;
import com.imdomestic.chorus.effect.target.*;
import java.util.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;

/** Observe actual collision movement; provisional player packet motion is published only after vanilla acceptance. */
public final class MinecraftGroundContact {
    private MinecraftGroundContact() {}
    private static final double SUPPORT_EPSILON = 1e-6;
    private static final ThreadLocal<Deque<Packet>> PACKETS = ThreadLocal.withInitial(ArrayDeque::new);
    public static final class Packet implements AutoCloseable {
        private final ServerPlayer player; private Optional<Move> landing = Optional.empty();
        private Packet(ServerPlayer player) { this.player = player; PACKETS.get().push(this); }
        @Override public void close() {
            if (PACKETS.get().pop() != this) throw new IllegalStateException("Movement packet scopes closed out of order");
            if (PACKETS.get().isEmpty()) PACKETS.remove();
        }
    }
    public static Packet packet(ServerPlayer player) { return new Packet(player); }
    private static Optional<Packet> packetFor(LivingEntity entity) { return PACKETS.get().stream().filter(p -> p.player == entity).findFirst(); }
    private static boolean supported(LivingEntity entity, ServerLevel level) {
        var box = entity.getBoundingBox();
        return level.noCollision(entity, box) && !level.noCollision(entity, box.move(0, -SUPPORT_EPSILON, 0));
    }
    public static Optional<Move> begin(Entity entity, Vec3 requested) {
        if (requested.y >= 0 || !(entity instanceof LivingEntity living) || !(entity.level() instanceof ServerLevel level)
                || entity.noPhysics || entity.isRemoved() || entity.isPassenger() || entity.isSpectator()
                || MinecraftCheckpointLifecycle.deferStop(level.getServer())) return Optional.empty();
        return MinecraftEffectRuntime.installed(level).filter(MinecraftEffectRuntime::observesLandings)
                .filter(_ -> !supported(living, level)).map(runtime -> new Move(runtime, living, level, living.getY(), requested.y,
                        new EntityAnchor.Reference(living.getUUID().toString(), MinecraftEntityLife.get(living).id(), level.dimension().identifier().toString())));
    }
    public static final class Move {
        private final MinecraftEffectRuntime runtime; private final LivingEntity entity; private final ServerLevel level;
        private final double beforeY, requestedY; private final EntityAnchor.Reference reference;
        private double movedY;
        private Move(MinecraftEffectRuntime runtime, LivingEntity entity, ServerLevel level, double beforeY, double requestedY, EntityAnchor.Reference reference) {
            this.runtime = runtime; this.entity = entity; this.level = level; this.beforeY = beforeY; this.requestedY = requestedY; this.reference = reference;
        }
        private boolean current() {
            return entity.level() == level && !entity.isRemoved() && !entity.noPhysics && !entity.isPassenger() && !entity.isSpectator()
                    && MinecraftEntityLife.get(entity).id().equals(reference.life()) && MinecraftEffectRuntime.installed(level).filter(r -> r == runtime).isPresent();
        }
        public void moved() {
            movedY = entity.getY() - beforeY;
            if (!current() || !supported(entity, level)) return;
            var packet = packetFor(entity);
            if (packet.isPresent()) packet.orElseThrow().landing = Optional.of(this); else publish();
        }
        private void publish() {
            if (!current() || !supported(entity, level)) return;
            var positions = new HashMap<PositionQuery, Optional<WorldPosition>>();
            for (var anchor : TargetQuery.Anchor.values()) positions.put(new PositionQuery(reference.target(), anchor), Optional.of(MinecraftWorldActions.observePosition(entity, anchor)));
            var observation = new EntityObservation(runtime.nowMicros(), Map.of(reference.target(), Optional.of(MinecraftWorldActions.observeEntity(entity))), positions);
            runtime.recordNativeLifecycle(new GroundContact.Landed(reference, "landing/" + UUID.randomUUID(), requestedY, movedY, observation).signal());
        }
    }
    /** Call only on vanilla's accepted position branch, after its final on-ground update. */
    public static void accepted(ServerPlayer player) {
        packetFor(player).ifPresent(packet -> { var fact = packet.landing; packet.landing = Optional.empty(); fact.ifPresent(Move::publish); });
    }
}
