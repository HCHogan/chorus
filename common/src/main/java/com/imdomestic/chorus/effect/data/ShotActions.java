package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.projectile.ShotGroups;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import java.util.Objects;

/** Explicit shot boundaries let content distinguish a trigger, burst, pellet and individual contact. */
public final class ShotActions {
    private ShotActions() {}
    public static int integer(double value) {
        if (!Double.isFinite(value) || value < 0 || value > Integer.MAX_VALUE || Math.rint(value) != value)
            throw new IllegalArgumentException("Pellet count/index must be a nonnegative integer");
        return (int) value;
    }
    public record Begin(Value pellets, Value lifetime) implements Action {
        public Begin { Objects.requireNonNull(pellets); Objects.requireNonNull(lifetime); }
        @Override public ResultShape validate(Validation v) {
            Validation.same(Unit.COUNT, pellets.unit(v)); Validation.same(Unit.SECOND, lifetime.unit(v));
            if (pellets instanceof Value.Constant c && integer(c.value()) == 0) throw new IllegalArgumentException("A shot needs pellets");
            if (lifetime instanceof Value.Constant c && c.value() <= 0) throw new IllegalArgumentException("A shot needs positive lifetime");
            return ResultShape.SHOT;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var op = e.context().operation(); long now = e.state().buffs().timeMicros();
            return ShotGroups.begin(e.state(), new ShotGroups.Handle("shot-group/" + op.frame() + "/" + op.pc() + "/" + op.invocation(),
                    e.origin(), integer(pellets.evaluate(e).value()), now, Math.addExact(now, Action.micros(lifetime, e))));
        }
    }
    public record Membership(String binding, Value pellet) {
        public Membership { Objects.requireNonNull(binding); Objects.requireNonNull(pellet); }
        public void validate(Validation v) {
            v.result(binding).requireShot(); Validation.same(Unit.COUNT, pellet.unit(v));
            if (pellet instanceof Value.Constant c) integer(c.value());
        }
        public ShotGroups.Member resolve(Evaluation e) {
            return new ShotGroups.Member((ShotGroups.Handle) e.context().bindings().get(binding), integer(pellet.evaluate(e).value()));
        }
    }
    public record Prepared(ProjectileFlight.Launch launch) implements RuleEngine.ActionResult {}
}
