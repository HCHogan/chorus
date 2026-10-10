package com.imdomestic.chorus.effect.buff;

import com.imdomestic.chorus.stat.Numbers;
import com.imdomestic.chorus.effect.target.WorldPosition;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/** Explicit state components used by counters, distinct-hit sets, references and history. */
public record BuffComponents(Map<String, Double> numbers, Map<String, Set<String>> sets, Map<String, String> references,
        Map<String, com.imdomestic.chorus.effect.target.Targets.Identities> targetSets, Map<String, Optional<WorldPosition>> positions,
        Map<String, Optional<com.imdomestic.chorus.effect.data.DamageSnapshot>> damageSnapshots) {
    public static final BuffComponents EMPTY = new BuffComponents(Map.of(), Map.of(), Map.of());
    public enum Update { SET, ADD, MAX, MIN }
    public BuffComponents {
        numbers = Map.copyOf(numbers);
        numbers.forEach((_, value) -> Numbers.finite(value, "buff component"));
        var copied = new HashMap<String, Set<String>>();
        sets.forEach((name, values) -> copied.put(name, Set.copyOf(values)));
        sets = Map.copyOf(copied);
        references = Map.copyOf(references);
        targetSets = Map.copyOf(targetSets);
        positions = Map.copyOf(positions);
        damageSnapshots = Map.copyOf(damageSnapshots);
    }
    public BuffComponents(Map<String, Double> numbers, Map<String, Set<String>> sets, Map<String, String> references,
            Map<String, com.imdomestic.chorus.effect.target.Targets.Identities> targetSets, Map<String, Optional<WorldPosition>> positions) {
        this(numbers, sets, references, targetSets, positions, Map.of());
    }
    public BuffComponents(Map<String, Double> numbers, Map<String, Set<String>> sets, Map<String, String> references) {
        this(numbers, sets, references, Map.of(), Map.of());
    }
    public BuffComponents(Map<String, Double> numbers, Map<String, Set<String>> sets, Map<String, String> references,
            Map<String, com.imdomestic.chorus.effect.target.Targets.Identities> targetSets) {
        this(numbers, sets, references, targetSets, Map.of());
    }
    public BuffComponents number(String name, Update update, double value) {
        Numbers.finite(value, "component update");
        var copy = new HashMap<>(numbers);
        Double previous = numbers.get(name);
        copy.put(name, switch (update) {
            case SET -> value;
            case ADD -> (previous == null ? 0 : previous) + value;
            case MAX -> previous == null ? value : Math.max(previous, value);
            case MIN -> previous == null ? value : Math.min(previous, value);
        });
        return new BuffComponents(copy, sets, references, targetSets, positions, damageSnapshots);
    }
    public BuffComponents remember(String name, String value) {
        var copy = new HashMap<>(sets);
        var members = new HashSet<>(sets.getOrDefault(name, Set.of()));
        members.add(value); copy.put(name, members);
        return new BuffComponents(numbers, copy, references, targetSets, positions, damageSnapshots);
    }
    public BuffComponents reference(String name, String value) {
        var copy = new HashMap<>(references); copy.put(name, value);
        return new BuffComponents(numbers, sets, copy, targetSets, positions, damageSnapshots);
    }
    public BuffComponents targets(String name, com.imdomestic.chorus.effect.target.Targets.Identities value) {
        if (!targetSets.containsKey(name)) throw new IllegalArgumentException("Missing target set component: " + name);
        var copy = new HashMap<>(targetSets); copy.put(name, value);
        return new BuffComponents(numbers, sets, references, copy, positions, damageSnapshots);
    }
    public BuffComponents position(String name, Optional<WorldPosition> value) {
        if (!positions.containsKey(name)) throw new IllegalArgumentException("Missing position component: " + name);
        var copy = new HashMap<>(positions); copy.put(name, value);
        return new BuffComponents(numbers, sets, references, targetSets, copy, damageSnapshots);
    }
    public BuffComponents damageSnapshot(String name, Optional<com.imdomestic.chorus.effect.data.DamageSnapshot> value) {
        if (!damageSnapshots.containsKey(name)) throw new IllegalArgumentException("Missing damage snapshot component: " + name);
        var copy = new HashMap<>(damageSnapshots); copy.put(name, value);
        return new BuffComponents(numbers, sets, references, targetSets, positions, copy);
    }
}
