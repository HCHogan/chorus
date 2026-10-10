package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.function.Predicate;

public final class ConstructActions {
    private ConstructActions() {}
    public static final ResultShape RESULT;
    static {
        var flags = new HashMap<String, Predicate<RuleEngine.ActionResult>>();
        for (var outcome : WorldConstruct.Outcome.values())
            flags.put(outcome.name().toLowerCase(Locale.ROOT), r -> ((WorldConstruct.Receipt) r).outcome() == outcome);
        RESULT = new ResultShape(Map.of("count", new ResultShape.Field(Unit.COUNT,
                r -> ((WorldConstruct.Receipt) r).targets().size())), flags, false, ResultShape.Reference.TARGET_IDENTITIES);
    }
    public record Spawn(String position, String kind, Value health, Value width, Value height, Value lifetime,
            ActionOrigin origin, Set<String> tags) implements Action {
        public Spawn { tags = Set.copyOf(tags); }
        @Override public ResultShape validate(Validation v) {
            v.result(position).requirePosition(); Action.validateDuration(lifetime, v);
            Validation.same(health.unit(v), Unit.DAMAGE); Validation.same(width.unit(v), Unit.METER); Validation.same(height.unit(v), Unit.METER);
            new WorldConstruct.Parameters(constant(health), constant(width), constant(height), 1);
            if (kind.isBlank()) throw new IllegalArgumentException("Missing construct kind");
            return RESULT;
        }
        private static double constant(Value value) { return value instanceof Value.Constant c ? c.value() : 1; }
        private static double read(Value value, Unit unit, Evaluation e) {
            var measured = value.evaluate(e); Validation.same(measured.unit(), unit); return measured.value();
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var parameters = new WorldConstruct.Parameters(read(health, Unit.DAMAGE, e), read(width, Unit.METER, e),
                    read(height, Unit.METER, e), Action.micros(lifetime, e));
            return new RuleEngine.Await<>(new WorldConstruct.Spawn(e.results().get(position).position(e.context().bindings().get(position)),
                    kind, origin.resolve(e), parameters, tags, e.program().orElseThrow().program().version()));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult result) {
            var receipt = (WorldConstruct.Receipt) result;
            if (!receipt.spawn().equals(e.context().command(WorldConstruct.Spawn.class))) throw new IllegalArgumentException("Construct receipt differs from issued command");
            return new RuleEngine.Local<>(e.state(), receipt, List.of());
        }
    }
}
