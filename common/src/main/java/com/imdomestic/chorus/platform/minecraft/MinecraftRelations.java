package com.imdomestic.chorus.platform.minecraft;

import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Query-time construct allegiance uses its original source owner, independently of physical identity. */
final class MinecraftRelations {
    private MinecraftRelations() {}
    private record Principal(String id, Optional<Entity> entity) {}
    private static Principal principal(Entity entity) {
        if (entity instanceof EffectConstruct construct && construct.construct().isPresent()) {
            String owner = construct.construct().orElseThrow().origin().owner();
            if (!owner.isBlank()) {
                Entity resolved = null;
                if (entity.level() instanceof ServerLevel level) {
                    try { resolved = level.getEntity(UUID.fromString(owner)); } catch (IllegalArgumentException ignored) { /* An unresolved logical owner has no observed team. */ }
                }
                return new Principal(owner, Optional.ofNullable(resolved).filter(e -> !e.isRemoved()));
            }
        }
        return new Principal(entity.getUUID().toString(), Optional.of(entity));
    }
    /** Missing owners do not turn unknown allegiance into hostility. Ownership identity itself survives absence. */
    static Optional<Boolean> allied(Entity left, Entity right) {
        if (left == right) return Optional.of(true);
        var a = principal(left); var b = principal(right);
        if (a.id().equals(b.id())) return Optional.of(true);
        if (a.entity().isEmpty() || b.entity().isEmpty()) return Optional.empty();
        return Optional.of(a.entity().orElseThrow().isAlliedTo(b.entity().orElseThrow()));
    }
}
