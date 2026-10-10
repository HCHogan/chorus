package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.random.RandomState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import java.util.*;

/** Randomness is an explicit state transition, never a Value evaluator with hidden side effects. */
public final class RandomActions {
    private RandomActions() {}
    public static final String EVENT = "chorus:random_sampled";
    public record Fact(EffectEvent event, RandomState.Sample sample) implements EffectEvent.Carrier {}
    public record Sample(RandomState.Distribution distribution, Value lower, Value upper) implements Action {
        public Sample { Objects.requireNonNull(distribution); Objects.requireNonNull(lower); Objects.requireNonNull(upper); }
        @Override public ResultShape validate(Validation v) {
            var unit = lower.unit(v); Validation.same(unit, upper.unit(v));
            if (lower instanceof Value.Constant l && upper instanceof Value.Constant u) RandomState.validate(distribution, new Measure(l.value(), unit), new Measure(u.value(), unit));
            return new ResultShape(Map.of("value", new ResultShape.Field(unit, r -> ((RandomState.Sample) r).value().value()),
                    "lower", new ResultShape.Field(unit, r -> ((RandomState.Sample) r).lower().value()), "upper", new ResultShape.Field(unit, r -> ((RandomState.Sample) r).upper().value())));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var sample = e.state().random().sample(distribution, lower.evaluate(e), upper.evaluate(e));
            var event = new EffectEvent(e.self(), "", e.origin(), e.origin().tags(), Map.of("value", sample.value(), "lower", sample.lower(), "upper", sample.upper()), Map.of(),
                    Map.of("algorithm", sample.algorithm(), "distribution", distribution.name().toLowerCase(Locale.ROOT),
                            "seed", Long.toUnsignedString(sample.before().seed(), 16), "cursor_before", Long.toString(sample.before().cursor()), "cursor_after", Long.toString(sample.after().cursor())));
            return new RuleEngine.Local<>(e.state().withRandom(sample.after()), sample, List.of(new RuleEngine.Signal(EVENT, new Fact(event, sample))));
        }
    }
}
