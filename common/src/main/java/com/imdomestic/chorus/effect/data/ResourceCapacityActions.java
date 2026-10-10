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
    public static final ResultShape RESULT = new ResultShape(Map.of(
            "before", new ResultShape.Field(Unit.CHARGE, r -> ((Resources.ResizeResult) r).before().value()),
            "after", new ResultShape.Field(Unit.CHARGE, r -> ((Resources.ResizeResult) r).after().value()),
            "before_capacity", new ResultShape.Field(Unit.CHARGE, r -> ((Resources.ResizeResult) r).before().capacity()),
            "capacity", new ResultShape.Field(Unit.CHARGE, r -> ((Resources.ResizeResult) r).after().capacity()),
            "discarded", new ResultShape.Field(Unit.CHARGE, r -> ((Resources.ResizeResult) r).discarded())),
            Map.of("changed", r -> ((Resources.ResizeResult) r).changed()));
    public record Resize(String resource, Evaluation.Target target, Value capacity) implements Action {
        public Resize { Objects.requireNonNull(resource); Objects.requireNonNull(target); Objects.requireNonNull(capacity); }
        @Override public ResultShape validate(Validation v) {
            v.target(target);
            if (!v.resource(resource).resizable()) throw new IllegalArgumentException("Resource is not resizable: " + resource);
            Validation.same(capacity.unit(v), Unit.CHARGE);
            if (capacity instanceof Value.Constant c) positive(c.value());
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
    private static void positive(double capacity) {
        Numbers.nonnegative(capacity, "resource capacity");
        if (capacity == 0) throw new IllegalArgumentException("Resource capacity must be positive");
    }
}
