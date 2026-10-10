package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;

/** Ammunition operations deliberately do not emit reload_finished or imply that a shot was fired. */
public final class AmmoActions {
    private AmmoActions() {}
    public record FiniteReserve(Value amount, Value capacity) {}
    public record ReserveSpec(Optional<FiniteReserve> finite) {
        public static final ReserveSpec UNLIMITED = new ReserveSpec(Optional.empty());
        void validate(Validation v) { finite.ifPresent(r -> { validateRounds(r.amount(), v); validateRounds(r.capacity(), v); }); }
        Optional<AmmoState.Reserve> resolve(Evaluation e) { return finite.map(r -> new AmmoState.Reserve(rounds(r.amount(), e), rounds(r.capacity(), e))); }
    }
    public record Observation(Optional<AmmoCapacity.View> view, boolean created) implements RuleEngine.ActionResult {
        public Observation { Objects.requireNonNull(view); if (created && view.isEmpty()) throw new IllegalArgumentException("Created ammunition is absent"); }
    }
    public record Change(Ammunition.Result mutation, AmmoCapacity.View view) implements RuleEngine.ActionResult {
        public Change { if (!mutation.after().equals(view.account())) throw new IllegalArgumentException("Mismatched ammunition result view"); }
    }
    private static Map<String, ResultShape.Field> fields(java.util.function.Function<RuleEngine.ActionResult, AmmoCapacity.View> state) {
        var fields = new HashMap<String, ResultShape.Field>();
        for (var field : AmmoState.Field.values()) fields.put(field.name().toLowerCase(Locale.ROOT), new ResultShape.Field(Unit.ROUND, r -> state.apply(r).read(field)));
        return fields;
    }
    public static final ResultShape OBSERVATION = new ResultShape(fields(r -> ((Observation) r).view().orElseThrow(() -> new IllegalArgumentException("Missing ammunition observation"))),
            Map.of("available", r -> ((Observation) r).view().isPresent(), "created", r -> ((Observation) r).created(),
                    "infinite_reserves", r -> ((Observation) r).view().filter(s -> s.account().reserve().isEmpty()).isPresent(),
                    "finite_reserves", r -> ((Observation) r).view().filter(s -> s.account().reserve().isPresent()).isPresent()));
    public static final ResultShape CHANGE;
    static {
        var fields = fields(r -> ((Change) r).view());
        fields.put("requested", new ResultShape.Field(Unit.ROUND, r -> ((Change) r).mutation().requested()));
        fields.put("applied", new ResultShape.Field(Unit.ROUND, r -> ((Change) r).mutation().applied()));
        fields.put("unfulfilled", new ResultShape.Field(Unit.ROUND, r -> ((Change) r).mutation().unfulfilled()));
        fields.put("magazine_delta", new ResultShape.Field(Unit.ROUND, r -> ((Change) r).mutation().after().magazine() - ((Change) r).mutation().before().magazine()));
        CHANGE = new ResultShape(fields, Map.of("complete", r -> ((Change) r).mutation().complete(), "changed", r -> ((Change) r).mutation().changed(),
                "infinite_reserves", r -> ((Change) r).mutation().after().reserve().isEmpty()));
    }
    private static void validateRounds(Value value, Validation v) {
        Validation.same(value.unit(v), Unit.ROUND); if (value instanceof Value.Constant c) AmmoState.rounds(c.value());
    }
    private static int rounds(Value value, Evaluation e) { var result = value.evaluate(e); Validation.same(result.unit(), Unit.ROUND); return AmmoState.rounds(result.value()); }
    private static RuleEngine.Local<EffectState> commit(Evaluation e, AmmoCapacity.View before, Ammunition.Result result) {
        var signals = new ArrayList<RuleEngine.Signal>(); var view = before.after(result.after()); var facts = AmmoFacts.changed(e.self(), e.origin(), result, view.capacity());
        if (result.applied() > 0) signals.add(new RuleEngine.Signal(switch (result.kind()) { case SPEND -> "chorus:ammo_spent"; case REFILL -> "chorus:ammo_refilled"; case GENERATE -> "chorus:ammo_generated"; }, facts));
        if (result.changed()) signals.add(new RuleEngine.Signal("chorus:ammo_changed", facts));
        return new RuleEngine.Local<>(e.state().withAmmo(result.after()), new Change(result, view), signals);
    }
    public record Initialize(Evaluation.Target weapon, Value capacity, Value magazine, ReserveSpec reserves, Optional<String> capacityProfile, Evaluation.Target holder) implements Action {
        public Initialize(Evaluation.Target weapon, Value capacity, Value magazine, ReserveSpec reserves) { this(weapon, capacity, magazine, reserves, Optional.empty(), Evaluation.Target.SELF); }
        @Override public ResultShape validate(Validation v) {
            v.target(weapon); v.target(holder); capacityProfile.ifPresent(v::ammoProfile); validateRounds(capacity, v); validateRounds(magazine, v); reserves.validate(v);
            if (capacity instanceof Value.Constant c && c.value() == 0) throw new IllegalArgumentException("Magazine capacity must be positive");
            if (reserves.finite().isPresent()) {
                var r = reserves.finite().orElseThrow();
                if (r.amount() instanceof Value.Constant a && r.capacity() instanceof Value.Constant c && a.value() > c.value()) throw new IllegalArgumentException("Initial reserves exceed capacity");
            }
            return OBSERVATION;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var desired = new AmmoState(e.target(weapon), rounds(magazine, e), rounds(capacity, e), reserves.resolve(e), capacityProfile.map(id -> new AmmoState.CapacityProfile(e.target(holder), id)));
            var existing = e.state().ammunition().get(desired.weapon());
            if (existing != null) {
                if (existing.capacity() != desired.capacity() || !existing.capacityProfile().equals(desired.capacityProfile()) || !existing.reserve().map(AmmoState.Reserve::capacity).equals(desired.reserve().map(AmmoState.Reserve::capacity))) throw new IllegalArgumentException("Existing ammunition capacity or reserve kind differs");
                return new RuleEngine.Local<>(e.state(), new Observation(Optional.of(e.ammoView(e.state(), existing)), false), List.of());
            }
            var next = e.state().withAmmo(desired);
            return new RuleEngine.Local<>(next, new Observation(Optional.of(e.ammoView(next, desired)), true), List.of());
        }
    }
    public record Observe(Evaluation.Target weapon) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(weapon); return OBSERVATION; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Local<>(e.state(), new Observation(Optional.ofNullable(e.state().ammunition().get(e.target(weapon))).map(account -> e.ammoView(e.state(), account)), false), List.of()); }
    }
    public record Spend(Evaluation.Target weapon, Ammunition.Pool pool, Value amount) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(weapon); validateRounds(amount, v); return CHANGE; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { var view = e.ammoView(weapon); return commit(e, view, Ammunition.spend(view.account(), pool, rounds(amount, e))); }
    }
    public record Refill(Evaluation.Target weapon, Optional<Value> amount, Optional<Value> ceiling) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(weapon); amount.ifPresent(x -> validateRounds(x, v)); ceiling.ifPresent(x -> validateRounds(x, v)); return CHANGE; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var view = e.ammoView(weapon); var state = view.account(); int limit = ceiling.map(x -> rounds(x, e)).orElse(view.capacity());
            return commit(e, view, Ammunition.refill(state, amount.map(x -> rounds(x, e)).orElse(Math.max(0, limit - state.magazine())), limit));
        }
    }
    public record Generate(Evaluation.Target weapon, Ammunition.Pool pool, Value amount, Optional<Value> ceiling) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(weapon); validateRounds(amount, v); ceiling.ifPresent(x -> validateRounds(x, v)); return CHANGE; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var view = e.ammoView(weapon); var state = view.account(); int limit = ceiling.map(x -> rounds(x, e)).orElse(pool == Ammunition.Pool.MAGAZINE ? view.capacity() : state.reserve().map(AmmoState.Reserve::capacity).orElse(0));
            return commit(e, view, Ammunition.generate(state, pool, rounds(amount, e), limit));
        }
    }
}
