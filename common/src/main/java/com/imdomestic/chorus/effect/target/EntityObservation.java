package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.effect.buff.BuffDefinition;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Receipt-time world evidence: omitted means unobserved, empty means observed unavailable. */
public record EntityObservation(long timeMicros, Map<String, Optional<EntityQuery.View>> entities) {
    public EntityObservation {
        if (timeMicros < 0 || timeMicros == BuffDefinition.FOREVER) throw new IllegalArgumentException("Invalid entity observation time");
        entities = Map.copyOf(entities);
        if (entities.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Missing observed entity identity");
    }
    public boolean observed(String target) { return entities.containsKey(target); }
    public Optional<EntityQuery.View> require(String target) {
        var view = entities.get(Objects.requireNonNull(target));
        if (view == null) throw new IllegalArgumentException("No event entity observation for: " + target);
        return view;
    }
}
