package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Compare-before-write replacement of a source set. Reactions see the complete resulting set. */
public record SourceBatch(List<Edit> edits) implements RuleEngine.Payload {
    public static final String EVENT = "chorus:internal/source_batch";
    public record Edit(String instance, Optional<EffectSource> before, Optional<EffectSource> after) {
        public Edit {
            Objects.requireNonNull(instance); Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (instance.isBlank() || before.filter(s -> !s.instance().equals(instance)).isPresent() || after.filter(s -> !s.instance().equals(instance)).isPresent()) throw new IllegalArgumentException("Invalid source edit");
        }
    }
    public record Receipt(List<EffectSource> detached, List<EffectSource> attached) implements RuleEngine.ActionResult {
        public Receipt { detached = List.copyOf(detached); attached = List.copyOf(attached); }
    }
    public SourceBatch {
        edits = edits.stream().sorted(Comparator.comparing(Edit::instance)).toList();
        var ids = new HashSet<String>();
        for (var edit : edits) if (!ids.add(edit.instance())) throw new IllegalArgumentException("Duplicate source edit: " + edit.instance());
    }
    public static SourceBatch between(Map<String, EffectSource> before, Map<String, EffectSource> after) {
        var ids = new TreeSet<>(before.keySet()); ids.addAll(after.keySet());
        return new SourceBatch(ids.stream().map(id -> new Edit(id, Optional.ofNullable(before.get(id)), Optional.ofNullable(after.get(id)))).toList());
    }
    public RuleEngine.Signal signal() { return new RuleEngine.Signal(EVENT, this); }
    public void validateCurrent(EffectState state) {
        for (var edit : edits) if (!edit.before().equals(Optional.ofNullable(state.sources().get(edit.instance())))) throw new IllegalStateException("Stale source batch: " + edit.instance());
    }
    public RuleEngine.Local<EffectState> apply(EffectState state) {
        validateCurrent(state);
        var updated = new TreeMap<>(state.sources()); var detached = new ArrayList<EffectSource>(); var attached = new ArrayList<EffectSource>();
        for (var edit : edits) {
            if (edit.before().equals(edit.after())) continue;
            edit.before().ifPresent(detached::add); edit.after().ifPresent(attached::add);
            if (edit.after().isPresent()) updated.put(edit.instance(), edit.after().orElseThrow()); else updated.remove(edit.instance());
        }
        if (detached.isEmpty() && attached.isEmpty()) return new RuleEngine.Local<>(state, new Receipt(detached, attached), List.of());
        var signals = new ArrayList<RuleEngine.Signal>();
        detached.forEach(source -> signals.add(SourceChange.fact(true, source)));
        attached.forEach(source -> signals.add(SourceChange.fact(false, source)));
        return new RuleEngine.Local<>(state.withSources(updated), new Receipt(detached, attached), signals);
    }
}
