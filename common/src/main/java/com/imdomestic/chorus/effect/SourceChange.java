package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** A source mutation enters through the timeline, so elapsed recovery is settled before detachment. */
public record SourceChange(String instance, Optional<EffectSource> replacement) implements RuleEngine.Payload {
    public static final String EVENT = "chorus:internal/source_change";
    public SourceChange {
        Objects.requireNonNull(replacement);
        if (instance.isBlank() || replacement.filter(source -> !source.instance().equals(instance)).isPresent()) throw new IllegalArgumentException("Invalid source mutation");
    }
    public static RuleEngine.Signal bind(EffectSource source) { return new RuleEngine.Signal(EVENT, new SourceChange(source.instance(), Optional.of(source))); }
    public static RuleEngine.Signal remove(String instance) { return new RuleEngine.Signal(EVENT, new SourceChange(instance, Optional.empty())); }
    /** Detached rules retain their old immutable source scope even after replacement at the same instance key. */
    public record Fact(EffectEvent event, EffectSource source, boolean detached) implements EffectEvent.Carrier {
        public Fact {
            Objects.requireNonNull(event); Objects.requireNonNull(source);
            if (!source.instance().equals(event.references().get("source_instance")) || !source.bundle().equals(event.references().get("bundle"))) throw new IllegalArgumentException("Source fact differs from its snapshot");
        }
    }
    public RuleEngine.Local<EffectState> apply(EffectState state) {
        return new SourceBatch(List.of(new SourceBatch.Edit(instance, Optional.ofNullable(state.sources().get(instance)), replacement))).apply(state);
    }
    static RuleEngine.Signal fact(boolean detached, EffectSource source) {
        var tags = new HashSet<>(source.origin().tags()); tags.addAll(source.tags());
        var event = new EffectEvent(source.holder(), source.holder(), source.origin(), tags, Map.of(), Map.of(), Map.of("source_instance", source.instance(), "bundle", source.bundle()));
        return new RuleEngine.Signal(detached ? "chorus:source_detached" : "chorus:source_attached", new Fact(event, source, detached));
    }
}
