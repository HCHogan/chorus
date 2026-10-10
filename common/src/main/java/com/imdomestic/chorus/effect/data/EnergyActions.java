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
    public enum AbilityOutcome { GRANTED, NO_SELECTION, NO_RESOURCE }
    public record AbilityResult(String holder, String slot, Optional<String> ability, AbilityOutcome outcome,
            Optional<Result> gain) implements RuleEngine.ActionResult {
        public Result granted() { return gain.orElseThrow(() -> new IllegalArgumentException("No ability energy grant: " + outcome)); }
    }
    /** Immutable observation of the selected base skill's account, before any later grants. */
    public record AbilityObservation(String holder, String slot, Optional<String> ability,
            Optional<ResourceState> account) implements RuleEngine.ActionResult {
        public AbilityObservation {
            Objects.requireNonNull(holder); Objects.requireNonNull(slot); Objects.requireNonNull(ability); Objects.requireNonNull(account);
            if (account.isPresent() && (ability.isEmpty() || !account.orElseThrow().key().holder().equals(holder))) {
                throw new IllegalArgumentException("Mismatched ability energy observation");
            }
        }
        public ResourceState observed() { return account.orElseThrow(() -> new IllegalArgumentException("No ability energy account to observe")); }
    }
    static final ResultShape ABILITY_OBSERVATION = new ResultShape(Map.of(
            "value", new ResultShape.Field(Unit.CHARGE, r -> ((AbilityObservation) r).observed().value()),
            "capacity", new ResultShape.Field(Unit.CHARGE, r -> ((AbilityObservation) r).observed().capacity()),
            "missing", new ResultShape.Field(Unit.CHARGE, r -> { var a = ((AbilityObservation) r).observed(); return a.capacity() - a.value(); }),
            "full_charges", new ResultShape.Field(Unit.COUNT, r -> StrictMath.floor(((AbilityObservation) r).observed().value()))), Map.of(
            "available", r -> ((AbilityObservation) r).account().isPresent(),
            "no_selection", r -> ((AbilityObservation) r).ability().isEmpty(),
            "no_resource", r -> { var a = (AbilityObservation) r; return a.ability().isPresent() && a.account().isEmpty(); },
            "full", r -> ((AbilityObservation) r).account().filter(a -> a.value() == a.capacity()).isPresent()));
    static AbilityObservation observeAbility(Evaluation e, String slot, Evaluation.Target target) {
        String holder = e.target(target);
        var ability = e.program().orElseThrow().selectedAbility(e.state(), holder, slot);
        var account = ability.flatMap(a -> a.cost()).map(cost -> e.resource(cost.resource(), target));
        return new AbilityObservation(holder, slot, ability.map(a -> a.id()), account);
    }
    public record ObserveAbility(String slot, Evaluation.Target target) implements Action {
        public ObserveAbility {
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(slot); Objects.requireNonNull(target);
        }
        @Override public ResultShape validate(Validation v) { v.target(target); return ABILITY_OBSERVATION; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            return new RuleEngine.Local<>(e.state(), observeAbility(e, slot, target), List.of());
        }
    }
    private static void validateBasis(EnergyGains.Basis basis, Map<String, Value> factors, Set<String> tags, Map<String, Value> numbers) {
        if (basis == EnergyGains.Basis.REFERENCE ? factors.isEmpty() : !factors.isEmpty()) {
            throw new IllegalArgumentException("Only reference-basis gains require included reference factors");
        }
        if (basis == EnergyGains.Basis.FIXED && (!tags.isEmpty() || !numbers.isEmpty())) throw new IllegalArgumentException("Fixed gains do not run a query");
        if (numbers.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Blank gain measurement name");
    }
    private static void validateValues(Validation v, Evaluation.Target target, Value amount, Map<String, Value> factors, Map<String, Value> numbers) {
        v.target(target); Validation.same(amount.unit(v), Unit.CHARGE);
        factors.values().forEach(value -> Validation.same(value.unit(v), Unit.MULTIPLIER));
        numbers.values().forEach(value -> value.unit(v));
    }
    private static final ResultShape GAIN = new ResultShape(Map.of(
            "requested", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).grant().requested()),
            "normalized", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).normalized().base()),
            "scaled", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).grant().scaled()),
            "credited", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).grant().credited()),
            "overflow", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).grant().overflow()),
            "after", new ResultShape.Field(Unit.CHARGE, r -> ((Result) r).grant().after().value())));
    private static final ResultShape ABILITY_GAIN;
    static {
        var fields = new HashMap<String, ResultShape.Field>();
        GAIN.fields().forEach((name, field) -> fields.put(name, new ResultShape.Field(field.unit(), r -> field.read().applyAsDouble(((AbilityResult) r).granted()))));
        ABILITY_GAIN = new ResultShape(fields, Map.of(
                "granted", r -> ((AbilityResult) r).outcome() == AbilityOutcome.GRANTED,
                "no_selection", r -> ((AbilityResult) r).outcome() == AbilityOutcome.NO_SELECTION,
                "no_resource", r -> ((AbilityResult) r).outcome() == AbilityOutcome.NO_RESOURCE));
    }
    /** Resolve the recipient's current base skill, then use its actual cost account and gain profile. */
    public record GrantAbility(String slot, Evaluation.Target target, Value amount, EnergyGains.Basis basis,
            Map<String, Value> referenceFactors, Set<String> tags, Map<String, Value> numbers) implements Action {
        public GrantAbility {
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(slot);
            Objects.requireNonNull(target); Objects.requireNonNull(amount); Objects.requireNonNull(basis);
            referenceFactors = Map.copyOf(referenceFactors); tags = Set.copyOf(tags); numbers = Map.copyOf(numbers);
            validateBasis(basis, referenceFactors, tags, numbers);
        }
        @Override public ResultShape validate(Validation v) { validateValues(v, target, amount, referenceFactors, numbers); return ABILITY_GAIN; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            String holder = e.target(target);
            var ability = e.program().orElseThrow().selectedAbility(e.state(), holder, slot);
            if (ability.isEmpty() || ability.orElseThrow().cost().isEmpty()) {
                return new RuleEngine.Local<>(e.state(), new AbilityResult(holder, slot, ability.map(a -> a.id()),
                        ability.isEmpty() ? AbilityOutcome.NO_SELECTION : AbilityOutcome.NO_RESOURCE, Optional.empty()), List.of());
            }
            var result = (RuleEngine.Local<EffectState>) new Grant(ability.orElseThrow().cost().orElseThrow().resource(), target, amount, basis, referenceFactors, tags, numbers).execute(e);
            return new RuleEngine.Local<>(result.state(), new AbilityResult(holder, slot, ability.map(a -> a.id()), AbilityOutcome.GRANTED,
                    Optional.of((Result) result.result())), result.emitted());
        }
    }
    public record Grant(String resource, Evaluation.Target target, Value amount, EnergyGains.Basis basis,
            Map<String, Value> referenceFactors, Set<String> tags, Map<String, Value> numbers) implements Action {
        public Grant {
            Objects.requireNonNull(resource); Objects.requireNonNull(target); Objects.requireNonNull(amount); Objects.requireNonNull(basis);
            referenceFactors = Map.copyOf(referenceFactors); tags = Set.copyOf(tags); numbers = Map.copyOf(numbers);
            validateBasis(basis, referenceFactors, tags, numbers);
        }
        @Override public ResultShape validate(Validation v) {
            validateValues(v, target, amount, referenceFactors, numbers); var definition = v.resource(resource);
            if (basis != EnergyGains.Basis.FIXED) {
                var id = definition.gainProfile().orElseThrow(() -> new IllegalArgumentException("Energy gain requires an explicit resource gain_profile"));
                var profile = v.profiles().get(id);
                if (profile == null) throw new IllegalArgumentException("Unknown energy gain profile: " + id);
                Validation.same(profile.inputUnit(), Unit.CHARGE); Validation.same(profile.outputUnit(), Unit.CHARGE);
            }
            return GAIN;
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
                var profile = e.resourceDefinition(resource).gainProfile().orElseThrow(() -> new IllegalArgumentException("Energy gain requires resource gain_profile: " + resource));
                var context = e.timerEvent(); var measurements = new HashMap<>(context.numbers());
                numbers.forEach((name, value) -> measurements.put(name, value.evaluate(e)));
                var refs = new HashMap<>(context.references()); refs.put("resource", resource);
                var query = new EffectEvent(context.actor(), context.victim(), e.origin(), tags, measurements, context.flags(), refs, context.impact()).withObservedBuffs(context.observedBuffs()).withObservedEntities(context.observedEntities());
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
