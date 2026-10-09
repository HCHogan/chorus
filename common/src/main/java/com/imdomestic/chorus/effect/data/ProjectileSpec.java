package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.stat.Unit;
import java.util.Optional;

/** Values resolve once when launched. Drag is a multiplier applied every 50 ms physical tick. */
public record ProjectileSpec(String position, String direction, Value speed, Value gravity, Value drag, Value lifetime, Collisions collision) {
    public ProjectileSpec(String position, String direction, Value speed, Value gravity, Value drag, Value lifetime) { this(position, direction, speed, gravity, drag, lifetime, Collisions.STOP); }
    public static final Unit SPEED = new Unit("chorus:meter_per_second"), GRAVITY = new Unit("chorus:meter_per_second_squared");
    public record LimitSpec(Optional<Value> value) {
        public static final LimitSpec UNLIMITED = new LimitSpec(Optional.empty()), ZERO = fixed(0), ONE = fixed(1);
        private static LimitSpec fixed(int value) { return new LimitSpec(Optional.of(new Value.Constant(value, Unit.COUNT))); }
        private static int count(double value) {
            if (!Double.isFinite(value) || value < 0 || value != Math.rint(value) || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Collision limit must be a nonnegative integral count");
            return (int) value;
        }
        void validate(Validation v, boolean positive) { value.ifPresent(n -> { Validation.same(n.unit(v), Unit.COUNT); if (n instanceof Value.Constant c && count(c.value()) == 0 && positive) throw new IllegalArgumentException("Per-target collision limit must be positive"); }); }
        ProjectileFlight.Limit resolve(Evaluation e) { return value.map(n -> new ProjectileFlight.Limit(count(measure(n, Unit.COUNT, e)))).orElse(ProjectileFlight.Limit.UNLIMITED); }
    }
    public record Collisions(LimitSpec blockBounces, LimitSpec entityPierces, LimitSpec hitsPerTarget, Value restitution) {
        public static final Collisions STOP = new Collisions(LimitSpec.ZERO, LimitSpec.ZERO, LimitSpec.ONE, new Value.Constant(1, Unit.MULTIPLIER));
        void validate(Validation v) {
            blockBounces.validate(v, false); entityPierces.validate(v, false); hitsPerTarget.validate(v, true); Validation.same(restitution.unit(v), Unit.MULTIPLIER);
            if (restitution instanceof Value.Constant c) new ProjectileFlight.Collision(new ProjectileFlight.Limit(0), new ProjectileFlight.Limit(0), new ProjectileFlight.Limit(1), c.value());
        }
        ProjectileFlight.Collision resolve(Evaluation e) { return new ProjectileFlight.Collision(blockBounces.resolve(e), entityPierces.resolve(e), hitsPerTarget.resolve(e), measure(restitution, Unit.MULTIPLIER, e)); }
    }
    public void validate(Validation v) {
        collision.validate(v);
        v.result(position).requirePosition(); v.result(direction).requireDirection();
        Validation.same(speed.unit(v), SPEED); Validation.same(gravity.unit(v), GRAVITY); Validation.same(drag.unit(v), Unit.MULTIPLIER);
        Action.validateDuration(lifetime, v);
        new ProjectileFlight.Parameters(speed instanceof Value.Constant s ? s.value() : 0,
                gravity instanceof Value.Constant g ? g.value() : 0, drag instanceof Value.Constant d ? d.value() : 1, 1);
    }
    private static double measure(Value value, Unit unit, Evaluation e) { var measured = value.evaluate(e); Validation.same(measured.unit(), unit); return measured.value(); }
    public ProjectileFlight.Parameters resolve(Evaluation e) {
        return new ProjectileFlight.Parameters(measure(speed, SPEED, e), measure(gravity, GRAVITY, e), measure(drag, Unit.MULTIPLIER, e), Action.micros(lifetime, e), collision.resolve(e));
    }
}
