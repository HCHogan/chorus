package com.imdomestic.chorus.effect.buff;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Bound buff sources for the rule interpreter, including the removed source of an ended event. */
public final class BuffRules<S> implements RuleEngine.RuleResolver<S> {
    public record Scope(BuffInstance snapshot, boolean ended) implements RuleEngine.Payload {
        /** Live components come from current state. Only the ended source reads its final snapshot. */
        public Optional<BuffInstance> current(BuffStore store) {
            if (ended) return Optional.of(snapshot);
            return store.active(snapshot.key()).filter(instance -> instance.generation() == snapshot.generation());
        }
        public boolean owns(Buffs.Change event) { return snapshot.generation() == event.instance().generation(); }
    }
    private final Map<BuffDefinition, List<RuleEngine.EventRule<S>>> bundles;
    private final List<RuleEngine.EventRule<S>> definitions;
    private final Function<S, BuffStore> buffs;

    public BuffRules(Map<BuffDefinition, List<RuleEngine.EventRule<S>>> bundles, Function<S, BuffStore> buffs) {
        this.buffs = java.util.Objects.requireNonNull(buffs);
        var copied = new LinkedHashMap<BuffDefinition, List<RuleEngine.EventRule<S>>>();
        var unique = new LinkedHashMap<String, RuleEngine.EventRule<S>>();
        var ids = new java.util.HashSet<String>();
        // Catalogue order does not determine source order; instances are sorted by key in BuffStore.
        bundles.entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(BuffDefinition::id))).forEach(entry -> {
            if (!ids.add(entry.getKey().id())) throw new IllegalArgumentException("Multiple versions of a buff definition in one catalogue");
            var wrapped = new ArrayList<RuleEngine.EventRule<S>>();
            for (var rule : entry.getValue()) {
                if (unique.containsKey(rule.definition())) throw new IllegalArgumentException("Duplicate buff rule definition: " + rule.definition());
                var bound = new RuleEngine.EventRule<S>(rule.definition(), rule.eventType(),
                        (state, context) -> ((Scope) context.scope()).current(buffs.apply(state)).isPresent() && rule.condition().test(state, context), rule.actions());
                unique.put(bound.definition(), bound); wrapped.add(bound);
            }
            copied.put(entry.getKey(), List.copyOf(wrapped));
        });
        this.bundles = Map.copyOf(copied); this.definitions = List.copyOf(unique.values());
    }

    public List<RuleEngine.EventRule<S>> definitions() { return definitions; }

    @Override public List<RuleEngine.RuleBinding> resolve(S state, RuleEngine.Event event) {
        var result = new ArrayList<RuleEngine.RuleBinding>();
        BuffStore store = buffs.apply(state);
        for (var instance : store.instances().values()) if (instance.activeCount(store.timeMicros()) > 0) {
            append(result, instance, event.signal().type(), false);
        }
        if (event.signal().type().equals("chorus:buff_ended") && event.signal().payload() instanceof Buffs.Change change && change.kind() == Buffs.Kind.ENDED) {
            append(result, change.before().orElseThrow(), event.signal().type(), true);
        }
        return List.copyOf(result);
    }

    private void append(List<RuleEngine.RuleBinding> result, BuffInstance instance, String eventType, boolean ended) {
        for (var rule : bundles.getOrDefault(instance.definition(), List.of())) if (rule.eventType().equals(eventType)) {
            result.add(new RuleEngine.RuleBinding("buff/" + instance.generation() + "/" + rule.definition(),
                    rule.definition(), new Scope(instance, ended)));
        }
    }
}
