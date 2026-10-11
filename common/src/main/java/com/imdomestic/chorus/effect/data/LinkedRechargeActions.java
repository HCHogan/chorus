package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.resource.LinkedRecharge;
import com.imdomestic.chorus.effect.resource.ResourceFacts;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;

/** Explicit completion of a linked cycle; the content owns start, pause, qualification and distribution. */
public final class LinkedRechargeActions {
    private LinkedRechargeActions() {}
    public static final ResultShape RESULT = new ResultShape(Map.of(
            "progress_before", new ResultShape.Field(Unit.CHARGE, r -> ((LinkedRecharge.Result) r).progressBefore().value()),
            "progress_after", new ResultShape.Field(Unit.CHARGE, r -> ((LinkedRecharge.Result) r).progressAfter().value()),
            "before", new ResultShape.Field(Unit.CHARGE, r -> ((LinkedRecharge.Result) r).charges().before().value()),
            "after", new ResultShape.Field(Unit.CHARGE, r -> ((LinkedRecharge.Result) r).charges().after().value()),
            "capacity", new ResultShape.Field(Unit.CHARGE, r -> ((LinkedRecharge.Result) r).charges().after().capacity()),
            "requested", new ResultShape.Field(Unit.CHARGE, r -> ((LinkedRecharge.Result) r).charges().requested()),
            "overflow", new ResultShape.Field(Unit.CHARGE, r -> ((LinkedRecharge.Result) r).charges().overflow()),
            "credited", new ResultShape.Field(Unit.CHARGE, r -> ((LinkedRecharge.Result) r).charges().credited())),
            Map.of("completed", r -> ((LinkedRecharge.Result) r).completed()));

    public record Complete(String progress, String resource, Evaluation.Target target, Optional<Value> amount) implements Action {
        public Complete(String progress, String resource, Evaluation.Target target) { this(progress, resource, target, Optional.empty()); }
        public Complete { Objects.requireNonNull(progress); Objects.requireNonNull(resource); Objects.requireNonNull(target); Objects.requireNonNull(amount); }
        @Override public ResultShape validate(Validation v) {
            v.target(target); var meter = v.resource(progress); v.resource(resource);
            if (progress.equals(resource) || meter.capacity() != 1 || meter.resizable())
                throw new IllegalArgumentException("Linked recharge requires a distinct, fixed one-unit progress resource");
            amount.ifPresent(value -> {
                Validation.same(value.unit(v), Unit.CHARGE);
                if (value instanceof Value.Constant constant) com.imdomestic.chorus.stat.Numbers.nonnegative(constant.value(), "recharge cycle yield");
            });
            return RESULT;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var definition = e.resourceDefinition(progress);
            if (definition.capacity() != 1 || definition.resizable())
                throw new IllegalArgumentException("Linked recharge progress definition must be fixed at one unit");
            var meter = e.resource(progress, target); var uses = e.resource(resource, target);
            // Qualify the pair before evaluating a contextual yield. An unfinished cycle has no yield query.
            var result = LinkedRecharge.complete(meter, uses);
            if (result.completed() && amount.isPresent()) {
                var requested = amount.orElseThrow().evaluate(e); Validation.same(requested.unit(), Unit.CHARGE);
                result = LinkedRecharge.complete(meter, uses, requested.value());
            }
            if (!result.completed()) return new RuleEngine.Local<>(e.state(), result, List.of());
            var signals = new ArrayList<RuleEngine.Signal>();
            signals.add(new RuleEngine.Signal("chorus:resource_changed",
                    ResourceFacts.change(result.progressBefore(), result.progressAfter(), e.origin(), "recharge_completed")));
            var granted = ResourceFacts.granted(result.charges(), e.origin(), "linked_recharge");
            signals.add(new RuleEngine.Signal("chorus:resource_granted", granted));
            if (result.charges().credited() > 0) signals.add(new RuleEngine.Signal("chorus:resource_changed", granted));
            return new RuleEngine.Local<>(e.state().withResource(result.progressAfter()).withResource(result.charges().after()), result, signals);
        }
    }
}
