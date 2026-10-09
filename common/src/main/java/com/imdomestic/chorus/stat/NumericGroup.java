package com.imdomestic.chorus.stat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Ordered tree of reducers. Paths are relative to the stage, e.g. melee/exclusive. */
public record NumericGroup(String name, Reduction reduction, Map<String, FamilyPolicy> families,
        List<NumericGroup> children) {
    public NumericGroup {
        Objects.requireNonNull(name);
        Objects.requireNonNull(reduction);
        families = Map.copyOf(families);
        children = List.copyOf(children);
        if (!name.matches("[a-zA-Z0-9_.:-]+")) throw new IllegalArgumentException("Invalid group name: " + name);
        if (children.stream().map(NumericGroup::name).distinct().count() != children.size()) {
            throw new IllegalArgumentException("Duplicate child group in " + name);
        }
    }

    public static NumericGroup leaf(String name, Reduction reduction) {
        return new NumericGroup(name, reduction, Map.of(), List.of());
    }

    public Set<String> paths() {
        var result = new HashSet<String>();
        collectPaths("", result);
        return Set.copyOf(result);
    }

    private void collectPaths(String prefix, Set<String> result) {
        String path = prefix + name;
        result.add(path);
        children.forEach(child -> child.collectPaths(path + "/", result));
    }

    public record Evaluation(String path, Reduction reduction, OptionalDouble value, Set<String> selected) {
        public Evaluation { selected = Set.copyOf(selected); }
    }
    public record Result(OptionalDouble value, Set<String> selected, List<Evaluation> trace) {
        public Result { selected = Set.copyOf(selected); trace = List.copyOf(trace); }
    }
    private record Candidate(double value, Set<String> ids) {}

    public Result evaluate(List<NumericContribution> contributions) {
        Set<String> validPaths = paths();
        var seen = new HashSet<String>();
        for (var c : contributions) {
            if (!validPaths.contains(c.group())) throw new IllegalArgumentException("Unknown group: " + c.group());
            if (!seen.add(c.id())) throw new IllegalArgumentException("Duplicate contribution: " + c.id());
        }
        return evaluate("", contributions);
    }

    private Result evaluate(String prefix, List<NumericContribution> contributions) {
        String path = prefix + name;
        var buckets = new TreeMap<String, List<NumericContribution>>();
        contributions.stream().filter(c -> c.group().equals(path))
                .sorted(Comparator.comparing(NumericContribution::id))
                .forEach(c -> buckets.computeIfAbsent(c.stackingKey(), _ -> new ArrayList<>()).add(c));
        var candidates = new ArrayList<Candidate>();
        buckets.forEach((family, values) -> candidates.addAll(applyFamily(
                families.getOrDefault(family, FamilyPolicy.INDEPENDENT), values)));
        var trace = new ArrayList<Evaluation>();
        for (var child : children) {
            Result r = child.evaluate(path + "/", contributions);
            trace.addAll(r.trace());
            r.value().ifPresent(value -> candidates.add(new Candidate(value, r.selected())));
        }
        OptionalDouble value = reduction.reduce(candidates.stream().map(Candidate::value).toList());
        Set<String> selected;
        if (reduction == Reduction.MAX && !candidates.isEmpty()) {
            selected = candidates.stream().max(Comparator.comparingDouble(Candidate::value)).orElseThrow().ids();
        } else {
            selected = candidates.stream().flatMap(c -> c.ids().stream()).collect(Collectors.toSet());
        }
        trace.add(new Evaluation(path, reduction, value, selected));
        return new Result(value, selected, trace);
    }

    private static Candidate candidate(NumericContribution c) {
        return new Candidate(c.amount().value(), Set.of(c.id()));
    }

    private static List<Candidate> applyFamily(FamilyPolicy policy, List<NumericContribution> values) {
        return switch (policy.kind()) {
            case INDEPENDENT -> values.stream().map(NumericGroup::candidate).toList();
            case UNIQUE -> {
                if (values.size() != 1) throw new IllegalArgumentException("Duplicate unique family: " + values.getFirst().stackingKey());
                yield List.of(candidate(values.getFirst()));
            }
            case MAX -> List.of(candidate(values.stream().max(Comparator.comparingDouble(c -> c.amount().value())).orElseThrow()));
            case PRIORITY -> List.of(candidate(values.stream().max(Comparator.comparingInt(NumericContribution::priority)
                    .thenComparingDouble(c -> c.amount().value())).orElseThrow()));
            case MAX_PER_SOURCE -> {
                var sources = new LinkedHashMap<String, NumericContribution>();
                for (var c : values) sources.merge(c.source().instance(), c,
                        (a, b) -> a.amount().value() >= b.amount().value() ? a : b);
                yield sources.values().stream().map(NumericGroup::candidate).toList();
            }
            case COUNT_TABLE -> {
                Double value = policy.countValues().get(values.size());
                if (value == null) throw new IllegalArgumentException("Missing family count: " + values.size());
                yield List.of(new Candidate(value, values.stream().map(NumericContribution::id).collect(Collectors.toSet())));
            }
        };
    }
}
