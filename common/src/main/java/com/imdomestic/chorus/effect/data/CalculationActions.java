package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.CalculationProfile;
import com.imdomestic.chorus.stat.CalculationPipeline;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Explicit read-only profile queries in action sequences, with immutable, capturable results. */
public final class CalculationActions {
    private CalculationActions() {}

    public record Result(String holder, EffectEvent query, CalculationProfile.Result calculation) implements RuleEngine.ActionResult {
        public Result { Objects.requireNonNull(holder); Objects.requireNonNull(query); Objects.requireNonNull(calculation); }
    }
    public record PipelineResult(String holder, EffectEvent query, CalculationPipeline.Result calculation) implements RuleEngine.ActionResult {
        public PipelineResult { Objects.requireNonNull(holder); Objects.requireNonNull(query); Objects.requireNonNull(calculation); }
    }
    private static EffectEvent query(Evaluation e, ActionOrigin origin, Set<String> tags, Map<String, Value> numbers, Optional<Evaluation.Target> victim) {
        var context = e.timerEvent(); var measurements = new HashMap<>(context.numbers());
        numbers.forEach((name, value) -> measurements.put(name, value.evaluate(e)));
        return new EffectEvent(context.actor(), victim.map(e::target).orElse(context.victim()), origin.resolve(e), tags, measurements,
                context.flags(), context.references(), context.impact()).withObservedBuffs(context.observedBuffs());
    }
    public record CalculatePipeline(List<String> profiles, Evaluation.Target target, Value input, ActionOrigin origin,
            Set<String> tags, Map<String, Value> numbers, Optional<Evaluation.Target> victim) implements Action {
        public CalculatePipeline {
            profiles = List.copyOf(profiles); tags = Set.copyOf(tags); numbers = Map.copyOf(numbers);
            Objects.requireNonNull(target); Objects.requireNonNull(input); Objects.requireNonNull(origin); Objects.requireNonNull(victim);
            if (profiles.isEmpty() || profiles.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Empty calculation pipeline or profile id");
            if (numbers.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Blank calculation measurement name");
        }
        @Override public ResultShape validate(Validation v) {
            v.target(target); victim.ifPresent(v::target); numbers.values().forEach(value -> value.unit(v));
            var pipeline = CalculationPipeline.resolve(profiles, v.profiles()); Validation.same(input.unit(v), pipeline.inputUnit());
            return new ResultShape(Map.of(
                    "input", new ResultShape.Field(pipeline.inputUnit(), r -> ((PipelineResult) r).calculation().input().value()),
                    "value", new ResultShape.Field(pipeline.outputUnit(), r -> ((PipelineResult) r).calculation().output().value())));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var query = query(e, origin, tags, numbers, victim); String holder = e.target(target);
            var result = e.program().orElseThrow(() -> new IllegalStateException("Calculation requires a compiled program"))
                    .calculatePipeline(e.state(), holder, query, profiles, input.evaluate(e));
            return new RuleEngine.Local<>(e.state(), new PipelineResult(holder, query, result), List.of());
        }
    }
    public record Calculate(String profile, Evaluation.Target target, Value input, ActionOrigin origin,
            Set<String> tags, Map<String, Value> numbers, Optional<Evaluation.Target> victim) implements Action {
        public Calculate {
            Objects.requireNonNull(profile); Objects.requireNonNull(target); Objects.requireNonNull(input); Objects.requireNonNull(origin);
            Objects.requireNonNull(victim);
            tags = Set.copyOf(tags); numbers = Map.copyOf(numbers);
            if (numbers.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Blank calculation measurement name");
        }
        public Calculate(String profile, Evaluation.Target target, Value input, ActionOrigin origin, Set<String> tags, Map<String, Value> numbers) {
            this(profile, target, input, origin, tags, numbers, Optional.empty());
        }
        @Override public ResultShape validate(Validation v) {
            v.target(target); victim.ifPresent(v::target);
            var definition = v.profiles().get(profile);
            if (definition == null) throw new IllegalArgumentException("Unknown calculation profile: " + profile);
            Validation.same(input.unit(v), definition.inputUnit());
            numbers.values().forEach(value -> value.unit(v));
            return new ResultShape(Map.of(
                    "input", new ResultShape.Field(definition.inputUnit(), result -> {
                        var measure = ((Result) result).calculation().inputs().base();
                        Validation.same(measure.unit(), definition.inputUnit()); return measure.value();
                    }),
                    "value", new ResultShape.Field(definition.outputUnit(), result -> {
                        var measure = ((Result) result).calculation().output();
                        Validation.same(measure.unit(), definition.outputUnit()); return measure.value();
                    })));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            // Query tags are explicitly declared; trigger tags must not accidentally qualify a different stat query.
            var query = query(e, origin, tags, numbers, victim);
            String holder = e.target(target);
            var calculated = e.program().orElseThrow(() -> new IllegalStateException("Calculation requires a compiled program"))
                    .calculate(e.state(), holder, query, profile, input.evaluate(e), List.of());
            return new RuleEngine.Local<>(e.state(), new Result(holder, query, calculated), List.of());
        }
    }
}
