package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.effect.buff.BuffDefinition;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Receipt-time world evidence: omitted means unobserved, empty means observed unavailable. */
public record EntityObservation(long timeMicros, Map<String, Optional<EntityQuery.View>> entities,
        Map<PositionQuery, Optional<WorldPosition>> positions) {
    /** Metadata-only adapters do not implicitly claim to have observed a position. */
    public EntityObservation(long timeMicros, Map<String, Optional<EntityQuery.View>> entities) { this(timeMicros, entities, Map.of()); }
    public EntityObservation {
        if (timeMicros < 0 || timeMicros == BuffDefinition.FOREVER) throw new IllegalArgumentException("Invalid entity observation time");
        entities = Map.copyOf(entities);
        positions = Map.copyOf(positions);
        if (entities.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Missing observed entity identity");
    }
    public boolean observed(String target) { return entities.containsKey(target); }
    public Optional<EntityQuery.View> require(String target) {
        var view = entities.get(Objects.requireNonNull(target));
        if (view == null) throw new IllegalArgumentException("No event entity observation for: " + target);
        return view;
    }
    public boolean positionObserved(String target, TargetQuery.Anchor anchor) { return positions.containsKey(new PositionQuery(target, anchor)); }
    public Optional<WorldPosition> requirePosition(String target, TargetQuery.Anchor anchor) {
        var position = positions.get(new PositionQuery(target, anchor));
        if (position == null) throw new IllegalArgumentException("No event position observation for: " + target + "/" + anchor);
        return position;
    }
}
