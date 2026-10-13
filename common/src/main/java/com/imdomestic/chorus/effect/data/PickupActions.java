package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.object.PickupCollection;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

public final class PickupActions {
    private PickupActions() {}
    public static final ResultShape RESULT = new ResultShape(Map.of("count", new ResultShape.Field(Unit.COUNT, r -> ((PickupCollection.Result) r).entries().size())),
            Map.of("found", r -> ((PickupCollection.Result) r).outcome() == PickupCollection.Outcome.FOUND,
                    "missing_collector", r -> ((PickupCollection.Result) r).outcome() == PickupCollection.Outcome.MISSING_COLLECTOR));
    /** Count is the candidate count. Claims/rewards run as queued reactions and may still be rejected. */
    public record Collect(Evaluation.Target collector, Value radius, Set<String> kinds, boolean lineOfSight) implements Action {
        public Collect {
            Objects.requireNonNull(collector); Objects.requireNonNull(radius); kinds = Set.copyOf(kinds);
            new PickupCollection.Query("", 0, kinds, lineOfSight);
        }
        @Override public ResultShape validate(Validation v) {
            v.target(collector); Validation.same(radius.unit(v), Unit.METER);
            if (radius instanceof Value.Constant c) Numbers.nonnegative(c.value(), "pickup collection radius");
            return RESULT;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var r = radius.evaluate(e); Validation.same(r.unit(), Unit.METER);
            return new RuleEngine.Await<>(new PickupCollection.Query(e.target(collector), r.value(), kinds, lineOfSight));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult value) {
            var result = (PickupCollection.Result) value;
            if (!result.query().equals(e.context().command(PickupCollection.Query.class))) throw new IllegalArgumentException("Pickup candidates differ from issued query");
            return new RuleEngine.Local<>(e.state(), result, result.entries().isEmpty() ? List.of()
                    : List.of(new PickupCollection.Batch(result.query(), result.entries()).signal()));
        }
    }
}
