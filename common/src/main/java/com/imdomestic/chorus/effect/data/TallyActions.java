package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.combat.DamageTallies;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.List;

public final class TallyActions {
    private TallyActions() {}
    private static DamageTallies.Handle handle(Evaluation e, String name) { return (DamageTallies.Handle) e.context().bindings().get(name); }
    public record Begin(Value duration) implements Action {
        @Override public ResultShape validate(Validation v) { Action.validateDuration(duration, v); return ResultShape.DAMAGE_TALLY; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var op = e.context().operation(); long now = e.state().buffs().timeMicros();
            return DamageTallies.begin(e.state(), new DamageTallies.Handle("damage-tally/" + op.frame() + "/" + op.pc() + "/" + op.invocation(), now, Math.addExact(now, Action.micros(duration, e))));
        }
    }
    public record Record(String tally, String damage) implements Action {
        @Override public ResultShape validate(Validation v) { v.result(tally).requireDamageTally(); v.result(damage).requireDamageReceipt(); return ResultShape.DAMAGE_TALLY_RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return DamageTallies.record(e.state(), handle(e, tally), (DamageReceipt) e.context().bindings().get(damage)); }
    }
    public record Read(String tally) implements Action {
        @Override public ResultShape validate(Validation v) { v.result(tally).requireDamageTally(); return ResultShape.DAMAGE_TALLY_RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Local<>(e.state(), DamageTallies.read(e.state(), handle(e, tally)), List.of()); }
    }
    public record Close(String tally) implements Action {
        @Override public ResultShape validate(Validation v) { v.result(tally).requireDamageTally(); return ResultShape.DAMAGE_TALLY_RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return DamageTallies.close(e.state(), handle(e, tally)); }
    }
}
