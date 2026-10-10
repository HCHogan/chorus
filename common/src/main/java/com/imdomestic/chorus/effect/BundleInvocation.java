package com.imdomestic.chorus.effect;

import java.util.Objects;

/** Explicit queued rule dispatch with a transient, immutable source; never a live equipment source. */
public record BundleInvocation(String version, EffectSource source, EffectEvent event) implements EffectEvent.Carrier {
    public BundleInvocation {
        Objects.requireNonNull(version); Objects.requireNonNull(source); Objects.requireNonNull(event);
        if (version.isBlank() || !source.origin().equals(event.source())
                || !source.instance().equals(event.references().get("source_instance"))
                || !source.bundle().equals(event.references().get("bundle"))) {
            throw new IllegalArgumentException("Inconsistent bundle invocation context");
        }
    }
}
