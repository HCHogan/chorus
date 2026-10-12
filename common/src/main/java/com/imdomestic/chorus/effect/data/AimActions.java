package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.motion.AimImpulse;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.function.Predicate;

public final class AimActions {
    private AimActions() {}
    public static final ResultShape RESULT;
    static {
        var fields = new HashMap<String, ResultShape.Field>();
        for (String phase : List.of("before", "after", "delta")) for (String axis : List.of("yaw", "pitch")) {
            fields.put(phase + "_" + axis, new ResultShape.Field(Unit.DEGREE, result -> {
                var c = ((AimImpulse.Receipt) result).requireChange();
                if (phase.equals("delta")) return axis.equals("yaw") ? c.yaw() : c.pitch();
                var rotation = phase.equals("before") ? c.before() : c.after();
                return axis.equals("yaw") ? rotation.yaw() : rotation.pitch();
            }));
        }
        var flags = new HashMap<String, Predicate<RuleEngine.ActionResult>>();
        for (var outcome : AimImpulse.Outcome.values())
            flags.put(outcome.name().toLowerCase(Locale.ROOT), result -> ((AimImpulse.Receipt) result).outcome() == outcome);
        flags.put("observed", result -> ((AimImpulse.Receipt) result).change().isPresent());
        flags.put("changed", result -> ((AimImpulse.Receipt) result).outcome() == AimImpulse.Outcome.APPLIED);
        RESULT = new ResultShape(fields, flags);
    }

    public record Apply(Evaluation.Target target, Value yaw, Value pitch, ActionOrigin origin, Set<String> tags) implements Action {
        public Apply {
            Objects.requireNonNull(target); Objects.requireNonNull(yaw); Objects.requireNonNull(pitch);
            Objects.requireNonNull(origin); tags = Set.copyOf(tags);
        }
        @Override public ResultShape validate(Validation v) {
            v.target(target);
            for (var value : List.of(yaw, pitch)) {
                Validation.same(value.unit(v), Unit.DEGREE);
                if (value instanceof Value.Constant c) AimImpulse.angle(c.value());
            }
            return RESULT;
        }
        private static double read(Value value, Evaluation e) {
            var number = value.evaluate(e); Validation.same(number.unit(), Unit.DEGREE); return number.value();
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            return new RuleEngine.Await<>(new AimImpulse.Command(e.target(target), read(yaw, e), read(pitch, e), origin.resolve(e), tags));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult result) {
            var receipt = (AimImpulse.Receipt) result;
            var command = e.context().command(AimImpulse.Command.class);
            if (!receipt.command().equals(command)) throw new IllegalArgumentException("Aim receipt differs from issued command");
            if (receipt.outcome() != AimImpulse.Outcome.APPLIED) return new RuleEngine.Local<>(e.state(), receipt, List.of());
            var numbers = new HashMap<String, Measure>();
            RESULT.fields().forEach((name, field) -> numbers.put(name, new Measure(field.read().applyAsDouble(receipt), field.unit())));
            var tags = new HashSet<>(command.origin().tags()); tags.addAll(command.tags()); tags.add("chorus:aim_impulse");
            var event = new EffectEvent(command.origin().owner(), command.target(), command.origin(), tags, numbers);
            return new RuleEngine.Local<>(e.state(), receipt, List.of(new RuleEngine.Signal("chorus:aim_impulse_applied", new AimImpulse.Applied(event, receipt))));
        }
    }
}
