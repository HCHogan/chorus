package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

public final class EnergyActions {
    private EnergyActions() {}
    public record RecipientScaling(double base, double value, Optional<CalculationProfile.Result> calculation) {
        public RecipientScaling { Numbers.nonnegative(base, "base gain scalar"); Numbers.nonnegative(value, "effective gain scalar"); Objects.requireNonNull(calculation); }
    }
    public record Scaling(EnergyGains.Normalized normalized, Optional<RecipientScaling> recipient,
            Optional<CalculationProfile.Result> calculation, double scaled) {}
    public sealed interface Gain extends RuleEngine.ActionResult permits Result, CyclicResult {
        EnergyGains.Normalized normalized(); Optional<RecipientScaling> recipient(); Optional<CalculationProfile.Result> calculation();
        double scaled(); double credited(); double overflow(); double after();
    }
    public record Result(EnergyGains.Normalized normalized, Optional<RecipientScaling> recipient, Optional<CalculationProfile.Result> calculation,
            ResourceResult grant) implements Gain {
        @Override public double scaled() { return grant.scaled(); }
        @Override public double credited() { return grant.credited(); }
        @Override public double overflow() { return grant.overflow(); }
        @Override public double after() { return grant.after().value(); }
    }
    public record CyclicResult(Scaling scaling, CyclicRecharge.Result recharge, Optional<CalculationProfile.Result> yieldCalculation) implements Gain {
        public double observedYield() { return yieldCalculation.orElseThrow(() -> new IllegalArgumentException("Partial packet did not query a cycle yield")).output().value(); }
        @Override public EnergyGains.Normalized normalized() { return scaling.normalized(); }
        @Override public Optional<RecipientScaling> recipient() { return scaling.recipient(); }
        @Override public Optional<CalculationProfile.Result> calculation() { return scaling.calculation(); }
        @Override public double scaled() { return scaling.scaled(); }
        @Override public double credited() { return recharge.credited(); }
        @Override public double overflow() { return recharge.overflow(); }
        @Override public double after() { return recharge.progressAfter().value(); }
    }
    public enum AbilityOutcome { GRANTED, NO_SELECTION, NO_RESOURCE, ALREADY_FULL }
    public record AbilityResult(String holder, String slot, Optional<String> ability, AbilityOutcome outcome,
            Optional<Gain> gain) implements RuleEngine.ActionResult {
        public Gain received() { return gain.orElseThrow(() -> new IllegalArgumentException("No ability energy grant: " + outcome)); }
        /** Legacy single-account receipt; cyclic grants expose their two-account result explicitly. */
        public Result granted() { if (received() instanceof Result result) return result; throw new IllegalArgumentException("Cyclic energy has a two-account receipt"); }
        public CyclicResult cyclic() { if (received() instanceof CyclicResult result) return result; throw new IllegalArgumentException("Not a cyclic energy grant"); }
    }
    /** Immutable observation of the selected base skill's usable energy and recharge destination. */
    public record AbilityObservation(String holder, String slot, Optional<String> ability,
            Optional<ResourceState> account, Optional<ResourceState> rechargeAccount) implements RuleEngine.ActionResult {
        public AbilityObservation(String holder, String slot, Optional<String> ability, Optional<ResourceState> account) {
            this(holder, slot, ability, account, account);
        }
        public AbilityObservation {
            Objects.requireNonNull(holder); Objects.requireNonNull(slot); Objects.requireNonNull(ability); Objects.requireNonNull(account); Objects.requireNonNull(rechargeAccount);
            if (account.isPresent() && (ability.isEmpty() || !account.orElseThrow().key().holder().equals(holder))) {
                throw new IllegalArgumentException("Mismatched ability energy observation");
            }
            if (account.isPresent() != rechargeAccount.isPresent() || rechargeAccount.filter(a -> !a.key().holder().equals(holder)
                    || a.timeMicros() != account.orElseThrow().timeMicros()
                    || a.key().equals(account.orElseThrow().key()) && !a.equals(account.orElseThrow())).isPresent())
                throw new IllegalArgumentException("Mismatched ability recharge observation");
        }
        public ResourceState observed() { return account.orElseThrow(() -> new IllegalArgumentException("No ability energy account to observe")); }
        public ResourceState rechargeObserved() { return rechargeAccount.orElseThrow(() -> new IllegalArgumentException("No ability recharge account to observe")); }
        public boolean separateRecharge() { return account.isPresent() && !observed().key().equals(rechargeObserved().key()); }
    }
    static final ResultShape ABILITY_OBSERVATION = new ResultShape(Map.of(
            "value", new ResultShape.Field(Unit.CHARGE, r -> ((AbilityObservation) r).observed().value()),
            "capacity", new ResultShape.Field(Unit.CHARGE, r -> ((AbilityObservation) r).observed().capacity()),
            "missing", new ResultShape.Field(Unit.CHARGE, r -> missing(((AbilityObservation) r).observed())),
            "full_charges", new ResultShape.Field(Unit.COUNT, r -> StrictMath.floor(((AbilityObservation) r).observed().value())),
            "recharge_value", new ResultShape.Field(Unit.CHARGE, r -> ((AbilityObservation) r).rechargeObserved().value()),
            "recharge_capacity", new ResultShape.Field(Unit.CHARGE, r -> ((AbilityObservation) r).rechargeObserved().capacity()),
            "recharge_missing", new ResultShape.Field(Unit.CHARGE, r -> missing(((AbilityObservation) r).rechargeObserved()))), Map.of(
            "available", r -> ((AbilityObservation) r).account().isPresent(),
            "no_selection", r -> ((AbilityObservation) r).ability().isEmpty(),
            "no_resource", r -> { var a = (AbilityObservation) r; return a.ability().isPresent() && a.account().isEmpty(); },
            "full", r -> ((AbilityObservation) r).account().filter(a -> a.value() == a.capacity()).isPresent(),
            "separate_recharge", r -> ((AbilityObservation) r).separateRecharge(),
            "recharge_full", r -> ((AbilityObservation) r).rechargeAccount().filter(a -> a.value() == a.capacity()).isPresent()));
    private static double missing(ResourceState account) {
        return java.math.BigDecimal.valueOf(account.capacity()).subtract(java.math.BigDecimal.valueOf(account.value())).doubleValue();
    }
    static AbilityObservation observeAbility(Evaluation e, String slot, Evaluation.Target target) {
        String holder = e.target(target);
        var ability = e.program().orElseThrow().selectedAbility(e.state(), holder, slot);
        var account = ability.flatMap(a -> a.cost()).map(cost -> e.resource(cost.resource(), target));
        var recharge = ability.flatMap(a -> a.energyResource()).map(resource -> e.resource(resource, target));
        return new AbilityObservation(holder, slot, ability.map(a -> a.id()), account, recharge);
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
            "requested", new ResultShape.Field(Unit.CHARGE, r -> ((Gain) r).normalized().requested()),
            "normalized", new ResultShape.Field(Unit.CHARGE, r -> ((Gain) r).normalized().base()),
            "recipient_scalar", new ResultShape.Field(Unit.MULTIPLIER, r -> ((Gain) r).recipient().orElseThrow(() -> new IllegalArgumentException("Fixed gains have no recipient scaling")).value()),
            "scaled", new ResultShape.Field(Unit.CHARGE, r -> ((Gain) r).scaled()),
            "credited", new ResultShape.Field(Unit.CHARGE, r -> ((Gain) r).credited()),
            "overflow", new ResultShape.Field(Unit.CHARGE, r -> ((Gain) r).overflow()),
            "after", new ResultShape.Field(Unit.CHARGE, r -> ((Gain) r).after())));
    private static final ResultShape ABILITY_GAIN;
    static {
        var fields = new HashMap<String, ResultShape.Field>();
        GAIN.fields().forEach((name, field) -> fields.put(name, new ResultShape.Field(field.unit(), r -> field.read().applyAsDouble(((AbilityResult) r).received()))));
        fields.put("cycles", new ResultShape.Field(Unit.COUNT, r -> ((AbilityResult) r).cyclic().recharge().cycles()));
        fields.put("uses_credited", new ResultShape.Field(Unit.CHARGE, r -> ((AbilityResult) r).cyclic().recharge().uses().credited()));
        fields.put("uses_after", new ResultShape.Field(Unit.CHARGE, r -> ((AbilityResult) r).cyclic().recharge().uses().after().value()));
        fields.put("yield", new ResultShape.Field(Unit.CHARGE, r -> ((AbilityResult) r).cyclic().observedYield()));
        ABILITY_GAIN = new ResultShape(fields, Map.of(
                "cyclic", r -> ((AbilityResult) r).gain().filter(g -> g instanceof CyclicResult).isPresent(),
                "yield_observed", r -> ((AbilityResult) r).gain().filter(g -> g instanceof CyclicResult c && c.yieldCalculation().isPresent()).isPresent(),
                "granted", r -> ((AbilityResult) r).outcome() == AbilityOutcome.GRANTED,
                "no_selection", r -> ((AbilityResult) r).outcome() == AbilityOutcome.NO_SELECTION,
                "no_resource", r -> ((AbilityResult) r).outcome() == AbilityOutcome.NO_RESOURCE,
                "already_full", r -> ((AbilityResult) r).outcome() == AbilityOutcome.ALREADY_FULL));
    }
    /** Resolve the recipient's current base skill, then its explicit recharge destination or legacy cost account. */
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
            var observed = observeAbility(e, slot, target);
            String holder = observed.holder(); var ability = observed.ability();
            if (observed.account().isEmpty()) {
                return new RuleEngine.Local<>(e.state(), new AbilityResult(holder, slot, ability,
                        ability.isEmpty() ? AbilityOutcome.NO_SELECTION : AbilityOutcome.NO_RESOURCE, Optional.empty()), List.of());
            }
            // A separate progress meter must not bank another cycle behind an already full usable account.
            // Direct resource grants remain explicit content operations; legacy single-account grants keep their receipts.
            if (observed.separateRecharge() && observed.observed().value() == observed.observed().capacity())
                return new RuleEngine.Local<>(e.state(), new AbilityResult(holder, slot, ability, AbilityOutcome.ALREADY_FULL, Optional.empty()), List.of());
            var definition = e.program().orElseThrow().selectedAbility(e.state(), holder, slot).orElseThrow();
            if (definition.rechargeYieldProfile().isPresent()) {
                var scaling = scale(e, observed.rechargeObserved().key().resource(), target, amount, basis, referenceFactors, tags, numbers);
                Optional<CalculationProfile.Result> yield = Optional.empty();
                var query = ResourceFacts.change(observed.rechargeObserved(), observed.rechargeObserved(), e.origin(), "grant");
                if (CyclicRecharge.completes(observed.rechargeObserved(), scaling.scaled()))
                    yield = Optional.of(e.program().orElseThrow().calculate(e.state(), holder, query, definition.rechargeYieldProfile().orElseThrow(), new Measure(1, Unit.CHARGE), List.of()));
                yield.ifPresent(r -> Validation.same(r.output().unit(), Unit.CHARGE));
                var recharge = CyclicRecharge.grant(observed.rechargeObserved(), observed.observed(), scaling.scaled(), yield.map(r -> r.output().value()).orElse(0.0));
                var receipt = new CyclicResult(scaling, recharge, yield);
                var after = e.state().withResource(recharge.progressAfter()).withResource(recharge.uses().after());
                var fact = cyclicFact(receipt, e.origin());
                var signals = new ArrayList<RuleEngine.Signal>();
                signals.add(new RuleEngine.Signal("chorus:resource_granted", fact));
                if (recharge.progressBefore().value() != recharge.progressAfter().value()) signals.add(new RuleEngine.Signal("chorus:resource_changed", fact));
                if (recharge.cycles() > 0) {
                    signals.add(new RuleEngine.Signal("chorus:recharge_completed", fact));
                    var uses = ResourceFacts.granted(recharge.uses(), e.origin(), "cyclic_recharge");
                    signals.add(new RuleEngine.Signal("chorus:resource_granted", uses));
                    if (recharge.uses().credited() > 0) signals.add(new RuleEngine.Signal("chorus:resource_changed", uses));
                }
                return new RuleEngine.Local<>(after, new AbilityResult(holder, slot, ability, AbilityOutcome.GRANTED, Optional.of(receipt)), signals);
            }
            var result = (RuleEngine.Local<EffectState>) new Grant(observed.rechargeObserved().key().resource(), target, amount, basis, referenceFactors, tags, numbers).execute(e);
            return new RuleEngine.Local<>(result.state(), new AbilityResult(holder, slot, ability, AbilityOutcome.GRANTED,
                    Optional.of((Gain) result.result())), result.emitted());
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
            var scaling = scale(e, resource, target, amount, basis, referenceFactors, tags, numbers);
            var normalized = scaling.normalized(); var recipient = scaling.recipient(); var calculation = scaling.calculation();
            var grant = Resources.grant(account, normalized.requested(), scaling.scaled());
            var payload = new Action.ResourceGranted(grant, e.origin());
            var signals = new ArrayList<RuleEngine.Signal>(); signals.add(new RuleEngine.Signal("chorus:resource_granted", payload));
            if (grant.after().value() != account.value()) signals.add(new RuleEngine.Signal("chorus:resource_changed", payload));
            return new RuleEngine.Local<>(e.state().withResource(grant.after()), new Result(normalized, recipient, calculation, grant), signals);
        }
    }
    private static Scaling scale(Evaluation e, String resource, Evaluation.Target target, Value amount, EnergyGains.Basis basis,
            Map<String, Value> referenceFactors, Set<String> tags, Map<String, Value> numbers) {
        var account = e.resource(resource, target);
        var requested = amount.evaluate(e); Validation.same(requested.unit(), Unit.CHARGE);
        var factors = new TreeMap<String, Double>();
        referenceFactors.forEach((name, value) -> {
            var factor = value.evaluate(e); Validation.same(factor.unit(), Unit.MULTIPLIER); factors.put(name, factor.value());
        });
        var normalized = EnergyGains.normalize(basis, requested.value(), factors);
        Optional<CalculationProfile.Result> calculation = Optional.empty();
        Optional<RecipientScaling> recipient = Optional.empty();
        double scaled = normalized.base();
        if (basis != EnergyGains.Basis.FIXED) {
            var definition = e.resourceDefinition(resource);
            var profile = definition.gainProfile().orElseThrow(() -> new IllegalArgumentException("Energy gain requires resource gain_profile: " + resource));
            var context = e.timerEvent(); var measurements = new HashMap<>(context.numbers());
            numbers.forEach((name, value) -> measurements.put(name, value.evaluate(e)));
            var refs = new HashMap<>(context.references()); refs.put("resource", resource);
            var query = new EffectEvent(context.actor(), context.victim(), e.origin(), tags, measurements, context.flags(), refs, context.impact()).withObservedBuffs(context.observedBuffs()).withObservedEntities(context.observedEntities());
            var scalarCalculation = definition.gainScalarProfile().map(id -> e.program().orElseThrow().calculate(e.state(), account.key().holder(), query, id,
                    new Measure(definition.gainScalar(), Unit.MULTIPLIER), List.of()));
            scalarCalculation.ifPresent(result -> Validation.same(result.output().unit(), Unit.MULTIPLIER));
            var scalar = new RecipientScaling(definition.gainScalar(), scalarCalculation.map(result -> result.output().value()).orElse(definition.gainScalar()), scalarCalculation);
            recipient = Optional.of(scalar);
            var result = e.program().orElseThrow().calculate(e.state(), account.key().holder(), query, profile,
                    new Measure(normalized.base() * scalar.value(), Unit.CHARGE), List.of());
            Validation.same(result.output().unit(), Unit.CHARGE);
            calculation = Optional.of(result); scaled = result.output().value();
        }
        Numbers.nonnegative(scaled, "scaled recharge energy");
        return new Scaling(normalized, recipient, calculation, scaled);
    }
    private static EffectEvent cyclicFact(CyclicResult result, BuffInstance.Origin origin) {
        var r = result.recharge(); var base = ResourceFacts.change(r.progressBefore(), r.progressAfter(), origin, "cyclic_recharge");
        var numbers = new HashMap<>(base.numbers());
        numbers.put("requested", new Measure(result.normalized().requested(), Unit.CHARGE));
        numbers.put("scaled", new Measure(result.scaled(), Unit.CHARGE));
        numbers.put("credited", new Measure(r.credited(), Unit.CHARGE));
        numbers.put("overflow", new Measure(r.overflow(), Unit.CHARGE));
        numbers.put("cycles", new Measure(r.cycles(), Unit.COUNT));
        if (result.yieldCalculation().isPresent()) numbers.put("yield", new Measure(result.observedYield(), Unit.CHARGE));
        numbers.put("uses_credited", new Measure(r.uses().credited(), Unit.CHARGE));
        numbers.put("uses_after", new Measure(r.uses().after().value(), Unit.CHARGE));
        var refs = new HashMap<>(base.references()); refs.put("uses_resource", r.uses().after().key().resource());
        var flags = new HashMap<>(base.flags()); flags.put("yield_observed", result.yieldCalculation().isPresent());
        return new EffectEvent(base.actor(), base.victim(), origin, base.tags(), numbers, flags, refs);
    }

}
