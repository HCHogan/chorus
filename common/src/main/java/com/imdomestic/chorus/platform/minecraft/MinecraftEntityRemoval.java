package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.target.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Capture at the first native removal boundary; publish only when removal was actually committed. */
public final class MinecraftEntityRemoval {
    private MinecraftEntityRemoval() {}
    public record Pending(MinecraftEffectRuntime runtime, EntityLifecycle.Removed fact) {
        public void committed() { runtime.recordNativeLifecycle(fact.signal()); }
    }
    public static Optional<Pending> begin(Entity entity, Entity.RemovalReason reason) {
        if (entity.isRemoved() || !(entity instanceof LivingEntity living) || !(entity.level() instanceof ServerLevel level)
                || MinecraftCheckpointLifecycle.deferStop(level.getServer())) return Optional.empty();
        return MinecraftEffectRuntime.installed(level).map(runtime -> {
            var reference = new EntityAnchor.Reference(living.getUUID().toString(), MinecraftEntityLife.get(living).id(), level.dimension().identifier().toString());
            var positions = new HashMap<PositionQuery, Optional<WorldPosition>>();
            for (var anchor : TargetQuery.Anchor.values()) positions.put(new PositionQuery(reference.target(), anchor), Optional.of(MinecraftWorldActions.observePosition(living, anchor)));
            var observation = new EntityObservation(runtime.nowMicros(), Map.of(reference.target(), Optional.empty()), positions);
            var kind = switch (reason) {
                case KILLED -> EntityLifecycle.Kind.DESTROYED;
                case DISCARDED -> EntityLifecycle.Kind.REMOVED;
                case UNLOADED_TO_CHUNK, UNLOADED_WITH_PLAYER -> EntityLifecycle.Kind.UNLOADED;
                case CHANGED_DIMENSION -> EntityLifecycle.Kind.TRANSFERRED;
            };
            return new Pending(runtime, new EntityLifecycle.Removed(reference, kind, "minecraft:" + reason.name().toLowerCase(Locale.ROOT), living.isAlive(), observation));
        });
    }
}
