package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

public final class EnergyActions {
    private EnergyActions() {}
    public record Result(EnergyGains.Normalized normalized, Optional<CalculationProfile.Result> calculation,
            ResourceResult grant) implements RuleEngine.ActionResult {}
    public record Grant(String resource, Evaluation.Target target, Value amount, EnergyGains.Basis basis,
            Map<String, Value> referenceFactors, Set<String> tags, Map<String, Value> numbers) implements Action {
        public Grant {
            Objects.requireNonNull(resource); Objects.requireNonNull(target); Objects.requireNonNull(amount); Objects.requireNonNull(basis);
            referenceFactors = Map.copyOf(referenceFactors); tags = Set.copyOf(tags); numbers = Map.copyOf(numbers);
            if (basis == EnergyGains.Basis.REFERENCE ? referenceFactors.isEmpty() : !referenceFactors.isEmpty()) {
                throw new IllegalArgumentException("Only reference-basis gains require included reference factors");
            }
            if (basis == EnergyGains.Basis.FIXED && (!tags.isEmpty() || !numbers.isEmpty())) throw new IllegalArgumentException("Fixed gains do not run a query");
            if (numbers.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Blank gain measurement name");
        }
        @Override public ResultShape validate(Validation v) {
            v.target(target); var definition = v.resource(resource); Validation.same(amount.unit(v), Unit.CHARGE);
            referenceFactors.values().forEach(value -> Validation.same(value.unit(v), Unit.MULTIPLIER));
            numbers.values().forEach(value -> value.unit(v));
            if (basis != EnergyGains.Basis.FIXED) {
                var id = definition.gainProfile().orElseThrow(() -> new IllegalArgumentException("Energy gain requires an explicit resource gain_profile"));
                var profile = v.profiles().get(id);
                if (profile == null) throw new IllegalArgumentException("Unknown energy gain profile: " + id);
                Validation.same(profile.inputUnit(), Unit.CHARGE); Validation.same(profile.outputUnit(), Unit.CHARGE);
            }
            return new ResultShape(Map.of(
                    "requested", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).grant().requested()),
                    "normalized", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).normalized().base()),
                    "scaled", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).grant().scaled()),
                    "credited", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).grant().credited()),
                    "overflow", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).grant().overflow()),
                    "after", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).grant().after().value())));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var account = e.resource(resource, target);
            var requested = amount.evaluate(e); Validation.same(requested.unit(), Unit.CHARGE);
            var factors = new TreeMap<String, Double>();
            referenceFactors.forEach((name, value) -> {
                var factor = value.evaluate(e); Validation.same(factor.unit(), Unit.MULTIPLIER); factors.put(name, factor.value());
            });
            var normalized = EnergyGains.normalize(basis, requested.value(), factors);
            Optional<CalculationProfile.Result> calculation = Optional.empty();
            double scaled = normalized.base();
            if (basis != EnergyGains.Basis.FIXED) {
                var profile = e.resourceDefinition(resource).gainProfile().orElseThrow();
                var context = e.timerEvent(); var measurements = new HashMap<>(context.numbers());
                numbers.forEach((name, value) -> measurements.put(name, value.evaluate(e)));
                var refs = new HashMap<>(context.references()); refs.put("resource", resource);
                var query = new EffectEvent(context.actor(), context.victim(), e.origin(), tags, measurements, context.flags(), refs, context.impact());
                var result = e.program().orElseThrow().calculate(e.state(), account.key().holder(), query, profile,
                        new Measure(normalized.base(), Unit.CHARGE), List.of());
                Validation.same(result.output().unit(), Unit.CHARGE);
                calculation = Optional.of(result); scaled = result.output().value();
            }
            var grant = Resources.grant(account, requested.value(), scaled);
            var payload = new Action.ResourceGranted(grant, e.origin());
            var signals = new ArrayList<RuleEngine.Signal>(); signals.add(new RuleEngine.Signal("chorus:resource_granted", payload));
            if (grant.after().value() != account.value()) signals.add(new RuleEngine.Signal("chorus:resource_changed", payload));
            return new RuleEngine.Local<>(e.state().withResource(grant.after()), new Result(normalized, calculation, grant), signals);
        }
    }
}
