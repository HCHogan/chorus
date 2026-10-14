package com.imdomestic.chorus.effect.actor;

import com.imdomestic.chorus.effect.EffectParameters;
import com.imdomestic.chorus.stat.Measure;
import java.util.*;

/** An owner's standing rules; SOURCE bundle includes provide composition without a second DSL. */
public record ActorBinding(String bundle, Set<String> tags, Map<String, Measure> parameters) {
    public ActorBinding {
        if (bundle == null || bundle.isBlank()) throw new IllegalArgumentException("Missing actor bundle");
        tags = Set.copyOf(tags); parameters = EffectParameters.copy(parameters);
    }
    public ActorBinding(String bundle) { this(bundle, Set.of(), Map.of()); }
}
