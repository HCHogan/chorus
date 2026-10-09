package com.imdomestic.chorus.stat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Validated mathematical pipeline. No Minecraft, entity state, or effect identifiers. */
public record CalculationProfile(String id, String version, Unit inputUnit, List<CalculationStep> steps) {
    public CalculationProfile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(version);
        Objects.requireNonNull(inputUnit);
        if (id.isBlank() || version.isBlank()) throw new IllegalArgumentException("Profile id and version are required");
        steps = List.copyOf(steps);
        var units = new LinkedHashMap<String, Unit>();
        units.put("base", inputUnit);
        Unit current = inputUnit;
        for (var step : steps) {
            if (step.id().isBlank() || units.containsKey(step.id())) {
                throw new IllegalArgumentException("Empty or duplicate stage: " + step.id());
            }
            if (step instanceof CalculationStep.Apply apply) {
                if (apply.operation() == NumericContribution.Operation.BASE_PERCENT
                        && !current.equals(units.get(apply.percentOf()))) {
                    throw new IllegalArgumentException("percent_of must refer to an earlier stage of the same unit: " + apply.percentOf());
                }
                validateGroupUnits(apply.group(), contributionUnit(apply, current));
            }
            if (step instanceof CalculationStep.Transform transform) current = transform.outputUnit();
            units.put(step.id(), current);
        }
    }

    /** Already evaluated operands only; replay never reads an entity, expression or mutable state. */
    public record Inputs(CalculationProfile profile, Measure base, List<NumericContribution> contributions, Set<String> omittedFactors) {
        public Inputs {
            Objects.requireNonNull(profile); Objects.requireNonNull(base);
            contributions = List.copyOf(contributions); omittedFactors = Set.copyOf(omittedFactors);
            for (var factor : omittedFactors) if (!factor.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid factor id: " + factor);
        }
    }
    public record Result(Measure output, CalculationTrace trace, Inputs inputs) {
        public Result { Objects.requireNonNull(output); Objects.requireNonNull(trace); Objects.requireNonNull(inputs); }
        public Result withoutFactors(Set<String> factors) {
            var omitted = new HashSet<>(inputs.omittedFactors()); omitted.addAll(factors);
            return inputs.profile().calculate(inputs.base(), inputs.contributions(), omitted);
        }
        public Result withBase(Measure base) { return inputs.profile().calculate(base, inputs.contributions(), inputs.omittedFactors()); }
        public Set<NumericContribution.Confidence> contributionConfidence() {
            return trace.contributions().stream().filter(CalculationTrace.ContributionTrace::selected)
                    .map(c -> c.contribution().source().confidence()).collect(Collectors.toUnmodifiableSet());
        }
    }

    public Unit outputUnit() {
        Unit result = inputUnit;
        for (var step : steps) if (step instanceof CalculationStep.Transform transform) result = transform.outputUnit();
        return result;
    }

    public Result calculate(Measure base, List<NumericContribution> input) {
        return calculate(base, input, Set.of());
    }
    private Result calculate(Measure base, List<NumericContribution> input, Set<String> omittedFactors) {
        if (!base.unit().equals(inputUnit)) throw new IllegalArgumentException("Wrong profile input unit: " + base.unit());
        // Stable input ordering also makes family ties independent of map / attachment iteration order.
        List<NumericContribution> contributions = input.stream().sorted(Comparator.comparing(NumericContribution::id)).toList();
        var inputs = new Inputs(this, base, contributions, omittedFactors);
        var seen = new HashSet<String>();
        for (var c : contributions) if (!seen.add(c.id())) throw new IllegalArgumentException("Duplicate contribution: " + c.id());
        Map<String, CalculationStep> stageDefinitions = steps.stream().collect(Collectors.toMap(CalculationStep::id, s -> s));
        for (var c : contributions) {
            if (!(stageDefinitions.get(c.stage()) instanceof CalculationStep.Apply apply)) {
                throw new IllegalArgumentException("Contribution references missing or non-modifiable stage: " + c.stage());
            }
            if (apply.operation() != c.operation() || !apply.percentOf().equals(c.percentOf())) {
                throw new IllegalArgumentException("Contribution operation / basis disagrees with stage: " + c.id());
            }
        }
        var values = new LinkedHashMap<String, Measure>();
        values.put("base", base);
        var trace = new ArrayList<CalculationTrace.StepTrace>();
        var factors = new ArrayList<CalculationTrace.FactorTrace>();
        var selected = new HashSet<String>();
        var omitted = new HashSet<String>();
        Measure current = base;
        for (var step : steps) {
            Measure before = current;
            List<NumericGroup.Evaluation> groupTrace = List.of();
            switch (step) {
                case CalculationStep.Apply apply -> {
                    Unit expected = contributionUnit(apply, current.unit());
                    List<NumericContribution> stageInput = contributions.stream().filter(c -> c.stage().equals(step.id())).toList();
                    for (var c : stageInput) if (!c.amount().unit().equals(expected)) {
                        throw new IllegalArgumentException("Wrong contribution unit in " + c.id() + ": expected " + expected);
                    }
                    var group = apply.group().evaluate(stageInput);
                    groupTrace = group.trace();
                    boolean skip = !apply.factor().isEmpty() && omittedFactors.contains(apply.factor());
                    if (skip) stageInput.forEach(c -> omitted.add(c.id()));
                    else selected.addAll(group.selected());
                    if (!apply.factor().isEmpty()) factors.add(new CalculationTrace.FactorTrace(apply.factor(), apply.id(), 1 + group.value().orElse(0), skip));
                    if (group.value().isPresent()) {
                        double v = group.value().getAsDouble();
                        double result = switch (apply.operation()) {
                            case ADD -> current.value() + v;
                            case BASE_PERCENT -> current.value() + values.get(apply.percentOf()).value() * v;
                            case MULTIPLY -> {
                                if (v < -1) throw new IllegalArgumentException("Combined multiplier delta below -1");
                                yield current.value() * (1 + v);
                            }
                            case RESIST -> current.value() * (1 - Numbers.fraction(v, "combined resistance"));
                            case REPLACE -> v;
                        };
                        current = new Measure(skip ? before.value() : result, current.unit());
                    }
                }
                case CalculationStep.Clamp clamp -> current = new Measure(Math.clamp(current.value(), clamp.minimum(), clamp.maximum()), current.unit());
                case CalculationStep.Transform transform -> current = new Measure(transform.curve().evaluate(current.value()), transform.outputUnit());
                case CalculationStep.Round round -> current = new Measure(switch (round.rounding()) {
                    case FLOOR -> Math.floor(current.value());
                    case CEIL -> Math.ceil(current.value());
                    case NEAREST_EVEN -> Math.rint(current.value());
                }, current.unit());
            }
            values.put(step.id(), current);
            trace.add(new CalculationTrace.StepTrace(step.id(), before, current, groupTrace));
        }
        return new Result(current, new CalculationTrace(id, version, values, trace, contributions.stream()
                .map(c -> new CalculationTrace.ContributionTrace(c, selected.contains(c.id()),
                        selected.contains(c.id()) ? "selected" : omitted.contains(c.id()) ? "named factor omitted" : "excluded by family or group selection")).toList(), factors), inputs);
    }

    private static Unit contributionUnit(CalculationStep.Apply step, Unit current) {
        return switch (step.operation()) {
            case ADD, REPLACE -> current;
            case BASE_PERCENT, MULTIPLY -> Unit.DELTA;
            case RESIST -> Unit.RESISTANCE;
        };
    }

    private static void validateGroupUnits(NumericGroup group, Unit unit) {
        if (group.reduction() == Reduction.PRODUCT && !unit.equals(Unit.DELTA)
                || group.reduction() == Reduction.RESIST && !unit.equals(Unit.RESISTANCE)) {
            throw new IllegalArgumentException("Reducer incompatible with unit at " + group.name());
        }
        group.children().forEach(child -> validateGroupUnits(child, unit));
    }
}
