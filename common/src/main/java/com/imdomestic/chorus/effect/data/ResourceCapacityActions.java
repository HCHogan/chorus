package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.resource.ResourceFacts;
import com.imdomestic.chorus.effect.resource.Resources;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;

/** Resizing is an explicit state transition, separate from energy grants, payments and numeric queries. */
public final class ResourceCapacityActions {
    private ResourceCapacityActions() {}
    public enum AbilityOutcome { RESIZED, NO_SELECTION, NO_RESOURCE, NOT_RESIZABLE }
    public record AbilityResult(String holder, String slot, Optional<String> ability, AbilityOutcome outcome,
            Optional<Resources.ResizeResult> resize) implements RuleEngine.ActionResult {
        public AbilityResult {
            if (holder == null || holder.isBlank()) throw new IllegalArgumentException("Missing ability capacity recipient");
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(slot);
            Objects.requireNonNull(ability); Objects.requireNonNull(outcome); Objects.requireNonNull(resize);
            ability.ifPresent(com.imdomestic.chorus.effect.ability.AbilityDefinition::id);
            if ((outcome == AbilityOutcome.NO_SELECTION) != ability.isEmpty()
                    || (outcome == AbilityOutcome.RESIZED) != resize.isPresent()
                    || resize.filter(r -> !r.after().key().holder().equals(holder)).isPresent())
                throw new IllegalArgumentException("Mismatched ability capacity receipt");
        }
        public Resources.ResizeResult resized() { return resize.orElseThrow(() -> new IllegalArgumentException("No ability capacity transition: " + outcome)); }
    }
    public static final ResultShape RESULT = new ResultShape(Map.of(
            "before", new ResultShape.Field(Unit.CHARGE, r -> ((Resources.ResizeResult) r).before().value()),
            "after", new ResultShape.Field(Unit.CHARGE, r -> ((Resources.ResizeResult) r).after().value()),
            "before_capacity", new ResultShape.Field(Unit.CHARGE, r -> ((Resources.ResizeResult) r).before().capacity()),
            "capacity", new ResultShape.Field(Unit.CHARGE, r -> ((Resources.ResizeResult) r).after().capacity()),
            "discarded", new ResultShape.Field(Unit.CHARGE, r -> ((Resources.ResizeResult) r).discarded())),
            Map.of("changed", r -> ((Resources.ResizeResult) r).changed()));
    public static final ResultShape ABILITY_RESULT;
    static {
        var fields = new HashMap<String, ResultShape.Field>();
        RESULT.fields().forEach((name, field) -> fields.put(name, new ResultShape.Field(field.unit(),
                r -> field.read().applyAsDouble(((AbilityResult) r).resized()))));
        ABILITY_RESULT = new ResultShape(fields, Map.of(
                "resized", r -> ((AbilityResult) r).outcome() == AbilityOutcome.RESIZED,
                "changed", r -> ((AbilityResult) r).resize().filter(Resources.ResizeResult::changed).isPresent(),
                "no_selection", r -> ((AbilityResult) r).outcome() == AbilityOutcome.NO_SELECTION,
                "no_resource", r -> ((AbilityResult) r).outcome() == AbilityOutcome.NO_RESOURCE,
                "not_resizable", r -> ((AbilityResult) r).outcome() == AbilityOutcome.NOT_RESIZABLE));
    }
    /** Resolve the recipient's current base selection once; capacity belongs to usable energy, not its recharge meter. */
    public record ResizeAbility(String slot, Evaluation.Target target, Value capacity) implements Action {
        public ResizeAbility {
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(slot);
            Objects.requireNonNull(target); Objects.requireNonNull(capacity);
        }
        @Override public ResultShape validate(Validation v) {
            v.target(target); validateCapacity(v, capacity); return ABILITY_RESULT;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var observed = EnergyActions.observeAbility(e, slot, target);
            if (observed.account().isEmpty())
                return new RuleEngine.Local<>(e.state(), new AbilityResult(observed.holder(), slot, observed.ability(),
                        observed.ability().isEmpty() ? AbilityOutcome.NO_SELECTION : AbilityOutcome.NO_RESOURCE, Optional.empty()), List.of());
            String resource = observed.observed().key().resource();
            // A generic equipment rule may see fixed-capacity skills. Report the unsupported target before querying a value.
            if (!e.resourceDefinition(resource).resizable())
                return new RuleEngine.Local<>(e.state(), new AbilityResult(observed.holder(), slot, observed.ability(),
                        AbilityOutcome.NOT_RESIZABLE, Optional.empty()), List.of());
            var transition = (RuleEngine.Local<EffectState>) new Resize(resource, target, capacity).execute(e);
            return new RuleEngine.Local<>(transition.state(), new AbilityResult(observed.holder(), slot, observed.ability(),
                    AbilityOutcome.RESIZED, Optional.of((Resources.ResizeResult) transition.result())), transition.emitted());
        }
    }
    public record Resize(String resource, Evaluation.Target target, Value capacity) implements Action {
        public Resize { Objects.requireNonNull(resource); Objects.requireNonNull(target); Objects.requireNonNull(capacity); }
        @Override public ResultShape validate(Validation v) {
            v.target(target);
            if (!v.resource(resource).resizable()) throw new IllegalArgumentException("Resource is not resizable: " + resource);
            validateCapacity(v, capacity);
            return RESULT;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            if (!e.resourceDefinition(resource).resizable()) throw new IllegalArgumentException("Resource is not resizable: " + resource);
            var account = e.resource(resource, target); var value = capacity.evaluate(e);
            Validation.same(value.unit(), Unit.CHARGE); positive(value.value());
            var result = Resources.resize(account, value.value());
            if (!result.changed()) return new RuleEngine.Local<>(e.state(), result, List.of());
            var fact = ResourceFacts.resized(result, e.origin()); var signals = new ArrayList<RuleEngine.Signal>();
            signals.add(new RuleEngine.Signal("chorus:resource_capacity_changed", fact));
            if (result.discarded() > 0) signals.add(new RuleEngine.Signal("chorus:resource_changed", fact));
            return new RuleEngine.Local<>(e.state().withResource(result.after()), result, signals);
        }
    }
    private static void validateCapacity(Validation v, Value capacity) {
        Validation.same(capacity.unit(v), Unit.CHARGE);
        if (capacity instanceof Value.Constant c) positive(c.value());
    }
    private static void positive(double capacity) {
        Numbers.nonnegative(capacity, "resource capacity");
        if (capacity == 0) throw new IllegalArgumentException("Resource capacity must be positive");
    }
}
