package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;
import java.util.Set;

/** An equipped/configured bundle instance. Removing it does not rewrite an already-started frame. */
public record EffectSource(String instance, String bundle, String holder, BuffInstance.Origin origin, Set<String> tags)
        implements RuleEngine.Payload {
    public EffectSource {
        Objects.requireNonNull(instance); Objects.requireNonNull(bundle); Objects.requireNonNull(holder); Objects.requireNonNull(origin);
        tags = Set.copyOf(tags);
        if (instance.isBlank() || bundle.isBlank() || holder.isBlank()) throw new IllegalArgumentException("Invalid effect source");
    }
}
