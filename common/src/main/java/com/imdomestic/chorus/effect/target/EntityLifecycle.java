package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Historical removal evidence. A removed entity is unavailable even when its last position is known. */
public final class EntityLifecycle {
    private EntityLifecycle() {}
    public static final String REMOVED = "chorus:entity_removed";
    public static final String DIED = "chorus:entity_life_died";
    public enum Kind { DESTROYED, REMOVED, UNLOADED, TRANSFERRED }
    /** The exact confirmed death boundary, which may precede both the final damage receipt and removal. */
    public record Died(EntityAnchor.Reference reference, String deathId, EntityObservation observation) implements EffectEvent.Carrier {
        public Died {
            Objects.requireNonNull(reference); Objects.requireNonNull(observation);
            if (deathId == null || deathId.isBlank()) throw new IllegalArgumentException("Missing confirmed death identity");
            if (!observation.entities().keySet().equals(Set.of(reference.target()))
                    || observation.require(reference.target()).isEmpty()
                    || observation.require(reference.target()).orElseThrow().alive())
                throw new IllegalArgumentException("Death must observe exactly its present dead entity");
            if (observation.positions().size() != TargetQuery.Anchor.values().length)
                throw new IllegalArgumentException("Death must capture all entity anchors");
            for (var anchor : TargetQuery.Anchor.values()) {
                var point = observation.requirePosition(reference.target(), anchor).orElseThrow(() -> new IllegalArgumentException("Missing death position"));
                if (!point.dimension().equals(reference.dimension())) throw new IllegalArgumentException("Death position belongs to another world");
            }
        }
        @Override public EffectEvent event() {
            return new EffectEvent("", reference.target(), new BuffInstance.Origin("", "chorus:entity_lifecycle", "", ""),
                    Set.of(DIED), Map.of(), Map.of("lethal", true),
                    Map.of("entity_life", reference.life(), "entity_dimension", reference.dimension(), "death_id", deathId))
                    .withObservedEntities(Optional.of(observation));
        }
        public RuleEngine.Signal signal() { return new RuleEngine.Signal(DIED, this); }
        public EntityAnchor.Result anchor(TargetQuery.Anchor anchor) {
            var query = new EntityAnchor.Query(reference.target(), anchor, Optional.of(reference));
            var last = new EntityAnchor.Observation(reference, observation.requirePosition(reference.target(), anchor).orElseThrow(), anchor, false);
            return new EntityAnchor.Result(query, EntityAnchor.Outcome.AVAILABLE, Optional.of(last));
        }
    }
    public record Removed(EntityAnchor.Reference reference, Kind kind, String reason, boolean wasAlive,
            EntityObservation observation) implements EffectEvent.Carrier {
        public Removed {
            Objects.requireNonNull(reference); Objects.requireNonNull(kind); Objects.requireNonNull(observation);
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Missing entity removal reason");
            if (!observation.entities().equals(Map.of(reference.target(), Optional.empty())))
                throw new IllegalArgumentException("Removal must observe exactly the removed entity as unavailable");
            if (observation.positions().size() != TargetQuery.Anchor.values().length)
                throw new IllegalArgumentException("Removal must capture all entity anchors");
            for (var anchor : TargetQuery.Anchor.values()) {
                var point = observation.requirePosition(reference.target(), anchor).orElseThrow(() -> new IllegalArgumentException("Missing removal position"));
                if (!point.dimension().equals(reference.dimension())) throw new IllegalArgumentException("Removal position belongs to another world");
            }
        }
        @Override public EffectEvent event() {
            return new EffectEvent("", reference.target(), new BuffInstance.Origin("", "chorus:entity_lifecycle", "", ""),
                    Set.of(REMOVED, "chorus:entity_removal_" + kind.name().toLowerCase(Locale.ROOT)), Map.of(), Map.of("was_alive", wasAlive),
                    Map.of("entity_life", reference.life(), "entity_dimension", reference.dimension(), "removal_reason", reason))
                    .withObservedEntities(Optional.of(observation));
        }
        public RuleEngine.Signal signal() { return new RuleEngine.Signal(REMOVED, this); }
        /** The returned anchor is historical; it does not claim the entity is currently resolvable. */
        public EntityAnchor.Result anchor(TargetQuery.Anchor anchor) {
            var query = new EntityAnchor.Query(reference.target(), anchor, Optional.of(reference));
            var last = new EntityAnchor.Observation(reference, observation.requirePosition(reference.target(), anchor).orElseThrow(), anchor, wasAlive);
            return new EntityAnchor.Result(query, EntityAnchor.Outcome.AVAILABLE, Optional.of(last));
        }
    }
}
