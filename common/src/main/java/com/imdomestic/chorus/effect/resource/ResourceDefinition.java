package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** One charge is always 1. These definitions currently describe sequential, shared energy accounts. */
public record ResourceDefinition(String id, double capacity, double initial, double baseRate,
        List<Double> thresholds, Optional<String> rateProfile) {
    public ResourceDefinition {
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid resource id");
        Numbers.nonnegative(capacity, "resource capacity"); Numbers.nonnegative(initial, "initial resource");
        Numbers.finite(baseRate, "resource base rate"); Objects.requireNonNull(rateProfile);
        if (capacity == 0 || initial > capacity) throw new IllegalArgumentException("Invalid resource capacity or initial value");
        var ordered = new TreeSet<Double>();
        for (double threshold : thresholds) {
            Numbers.nonnegative(threshold, "resource threshold");
            if (threshold > capacity || !ordered.add(threshold)) throw new IllegalArgumentException("Invalid or duplicate resource threshold");
        }
        thresholds = List.copyOf(ordered);
    }
    public ResourceState initialize(String holder, long time) { return new ResourceState(new ResourceState.Key(holder, id), initial, capacity, time); }
    public void validate(ResourceState state) {
        if (!id.equals(state.key().resource()) || state.capacity() != capacity) throw new IllegalArgumentException("Resource account disagrees with its definition: " + id);
    }
    public void threshold(double value) {
        Numbers.nonnegative(value, "resource threshold");
        if (value != 0 && value != capacity && !thresholds.contains(value)) throw new IllegalArgumentException("Undeclared reaction threshold: " + value);
    }
}
