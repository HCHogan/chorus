package com.imdomestic.chorus.stat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CalculationTrace(String profile, String version, Map<String, Measure> stages,
        List<StepTrace> steps, List<ContributionTrace> contributions, List<FactorTrace> factors) {
    public CalculationTrace {
        stages = Collections.unmodifiableMap(new LinkedHashMap<>(stages));
        steps = List.copyOf(steps);
        contributions = List.copyOf(contributions);
        factors = List.copyOf(factors);
    }
    public CalculationTrace(String profile, String version, Map<String, Measure> stages, List<StepTrace> steps, List<ContributionTrace> contributions) {
        this(profile, version, stages, steps, contributions, List.of());
    }

    /** Multiplier comes from the resolved group, including when the stage input is zero. */
    public record FactorTrace(String factor, String stage, double multiplier, boolean omitted) {}

    public record StepTrace(String stage, Measure before, Measure after, List<NumericGroup.Evaluation> groups) {
        public StepTrace { groups = List.copyOf(groups); }
    }

    public record ContributionTrace(NumericContribution contribution, boolean selected, String reason) {}
}
