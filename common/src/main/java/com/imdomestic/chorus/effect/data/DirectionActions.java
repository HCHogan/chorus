package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Optional;

/** Pure operations on captured axes; never resample actor rotation or invent a missing frame. */
public final class DirectionActions {
    private DirectionActions() {}
    private static Optional<WorldDirection> read(Evaluation e, String binding) { return e.results().get(binding).direction(e.context().bindings().get(binding)); }
    public record Cross(String left, String right) implements Action {
        @Override public ResultShape validate(Validation v) { v.result(left).requireDirection(); v.result(right).requireDirection(); return ResultShape.DIRECTION; }
        @Override public RuleEngine.Local<EffectState> execute(Evaluation e) {
            var a = read(e, left); var b = read(e, right);
            var result = a.flatMap(x -> b.flatMap(x::cross));
            return new RuleEngine.Local<>(e.state(), new DirectionResult.Stored(result), java.util.List.of());
        }
    }
    public record Rotate(String direction, String axis, Value angle) implements Action {
        @Override public ResultShape validate(Validation v) { v.result(direction).requireDirection(); v.result(axis).requireDirection(); Validation.same(angle.unit(v), ProjectileSpec.ANGLE); return ResultShape.DIRECTION; }
        @Override public RuleEngine.Local<EffectState> execute(Evaluation e) {
            var a = read(e, direction); var b = read(e, axis); var degrees = angle.evaluate(e); Validation.same(degrees.unit(), ProjectileSpec.ANGLE);
            var result = a.flatMap(x -> b.flatMap(y -> x.rotate(y, degrees.value())));
            return new RuleEngine.Local<>(e.state(), new DirectionResult.Stored(result), java.util.List.of());
        }
    }
}
