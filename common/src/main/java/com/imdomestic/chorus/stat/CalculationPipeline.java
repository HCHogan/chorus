package com.imdomestic.chorus.stat;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/** An explicit, finite sequence of profiles. Each occurrence keeps its own contributions and trace. */
public record CalculationPipeline(List<CalculationProfile> profiles) {
    public CalculationPipeline {
        profiles = List.copyOf(profiles);
        if (profiles.isEmpty()) throw new IllegalArgumentException("Empty calculation pipeline");
        Unit current = profiles.getFirst().inputUnit();
        for (var profile : profiles) {
            if (!current.equals(profile.inputUnit())) throw new IllegalArgumentException("Pipeline unit mismatch before " + profile.id());
            current = profile.outputUnit();
        }
    }
    public static CalculationPipeline resolve(List<String> ids, Map<String, CalculationProfile> catalogue) {
        return new CalculationPipeline(ids.stream().map(id -> {
            var profile = catalogue.get(id);
            if (profile == null) throw new IllegalArgumentException("Unknown pipeline profile: " + id);
            return profile;
        }).toList());
    }
    public Unit inputUnit() { return profiles.getFirst().inputUnit(); }
    public Unit outputUnit() { return profiles.getLast().outputUnit(); }
    /** The collector is used only now; the returned result retains no callback or mutable domain state. */
    public Result calculate(Measure base, BiFunction<CalculationProfile, Measure, CalculationProfile.Result> collect) {
        Objects.requireNonNull(collect);
        if (!base.unit().equals(inputUnit())) throw new IllegalArgumentException("Wrong pipeline input unit");
        var results = new ArrayList<CalculationProfile.Result>(); Measure current = base;
        for (var profile : profiles) {
            var result = collect.apply(profile, current);
            if (!result.inputs().profile().equals(profile) || !result.inputs().base().equals(current) || !result.output().unit().equals(profile.outputUnit())) {
                throw new IllegalArgumentException("Pipeline collector returned a different profile, input or output unit");
            }
            results.add(result); current = result.output();
        }
        return new Result(base, results);
    }
    public record Result(Measure input, List<CalculationProfile.Result> steps) {
        public Result {
            Objects.requireNonNull(input); steps = List.copyOf(steps);
            if (steps.isEmpty()) throw new IllegalArgumentException("Empty pipeline result");
            Measure current = input;
            for (var step : steps) {
                if (!step.inputs().base().equals(current)) throw new IllegalArgumentException("Disconnected pipeline result");
                current = step.output();
            }
        }
        public Measure output() { return steps.getLast().output(); }
        public Set<NumericContribution.Confidence> contributionConfidence() {
            return steps.stream().flatMap(step -> step.contributionConfidence().stream()).collect(Collectors.toUnmodifiableSet());
        }
        public Result withBase(Measure base) { return replay(base, Set.of()); }
        public Result withoutFactors(Set<String> factors) { return replay(input, Set.copyOf(factors)); }
        private Result replay(Measure base, Set<String> factors) {
            var replayed = new ArrayList<CalculationProfile.Result>(); Measure current = base;
            for (var step : steps) {
                var result = step.withBase(current);
                if (!factors.isEmpty()) result = result.withoutFactors(factors);
                replayed.add(result); current = result.output();
            }
            return new Result(base, replayed);
        }
    }
}
