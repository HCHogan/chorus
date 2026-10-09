package com.imdomestic.chorus.effect.buff;

import com.imdomestic.chorus.stat.Measure;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Declared component names, units and initial values for data-defined state. */
public record BuffSchema(Map<String, Measure> numbers, Set<String> sets, Set<String> references, Set<String> targetSets, Set<String> positions) {
    public static final BuffSchema EMPTY = new BuffSchema(Map.of(), Set.of(), Set.of());
    public BuffSchema {
        numbers = Map.copyOf(numbers); sets = Set.copyOf(sets); references = Set.copyOf(references); targetSets = Set.copyOf(targetSets); positions = Set.copyOf(positions);
        var names = new java.util.HashSet<String>();
        for (String name : numbers.keySet()) if (!names.add(name) || !name.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("Invalid component name");
        for (String name : sets) if (!names.add(name) || !name.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("Duplicate/invalid component name");
        for (String name : references) if (!names.add(name) || !name.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("Duplicate/invalid component name");
        for (String name : targetSets) if (!names.add(name) || !name.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("Duplicate/invalid component name");
        for (String name : positions) if (!names.add(name) || !name.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("Duplicate/invalid component name");
    }
    public BuffSchema(Map<String, Measure> numbers, Set<String> sets, Set<String> references) { this(numbers, sets, references, Set.of(), Set.of()); }
    public BuffSchema(Map<String, Measure> numbers, Set<String> sets, Set<String> references, Set<String> targetSets) { this(numbers, sets, references, targetSets, Set.of()); }
    public void requireTargets(String name) { if (!targetSets.contains(name)) throw new IllegalArgumentException("Undeclared target set component: " + name); }
    public void requirePosition(String name) { if (!positions.contains(name)) throw new IllegalArgumentException("Undeclared position component: " + name); }
    public Measure number(String name) {
        var definition = numbers.get(name); if (definition == null) throw new IllegalArgumentException("Undeclared numeric component: " + name); return definition;
    }
    public BuffComponents initial() {
        var values = new HashMap<String, Double>(); numbers.forEach((name, value) -> values.put(name, value.value()));
        var members = new HashMap<String, Set<String>>(); sets.forEach(name -> members.put(name, Set.of()));
        var targets = new HashMap<String, com.imdomestic.chorus.effect.target.Targets.Identities>();
        targetSets.forEach(name -> targets.put(name, com.imdomestic.chorus.effect.target.Targets.Identities.EMPTY));
        var places = new HashMap<String, java.util.Optional<com.imdomestic.chorus.effect.target.WorldPosition>>();
        positions.forEach(name -> places.put(name, java.util.Optional.empty()));
        return new BuffComponents(values, members, Map.of(), targets, places);
    }
}
