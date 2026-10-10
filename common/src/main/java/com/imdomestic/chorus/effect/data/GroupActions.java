package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import java.util.Optional;

public final class GroupActions {
    private GroupActions() {}
    public record Begin(Value lifetime, ActionOrigin origin) implements Action {
        public Begin { java.util.Objects.requireNonNull(lifetime); java.util.Objects.requireNonNull(origin); }
        @Override public ResultShape validate(Validation v) {
            Validation.same(lifetime.unit(v), Unit.SECOND);
            if (lifetime instanceof Value.Constant c && c.value() <= 0) throw new IllegalArgumentException("Damage group requires positive lifetime");
            return ResultShape.DAMAGE_GROUP;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var op = e.context().operation(); long start = e.state().buffs().timeMicros();
            long duration = Action.micros(lifetime, e);
            if (duration <= 0) throw new IllegalArgumentException("Damage group requires positive lifetime");
            return DamageGroups.begin(e.state(), new DamageGroups.Handle("damage-group/" + op.frame() + "/" + op.pc() + "/" + op.invocation(), origin.resolve(e).owner(), start, Math.addExact(start, duration)));
        }
    }
    public record End(String group) implements Action {
        @Override public ResultShape validate(Validation v) { v.result(group).requireDamageGroup(); return ResultShape.EMPTY; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return DamageGroups.close(e.state(), (DamageGroups.Handle) e.context().bindings().get(group)); }
    }
    static void validate(Optional<String> binding, Validation v) { binding.ifPresent(name -> v.result(name).requireDamageGroup()); }
    static DamageCommand resolve(Optional<String> binding, Evaluation e, DamageCommand command) {
        return binding.map(name -> command.withGroup((DamageGroups.Handle) e.context().bindings().get(name))).orElse(command);
    }
}
