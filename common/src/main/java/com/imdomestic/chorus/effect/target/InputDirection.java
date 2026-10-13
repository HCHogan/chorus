package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;
import java.util.Optional;

/** Observed movement intent, not velocity or facing. A neutral input is distinct from an unavailable observation. */
public final class InputDirection {
    private InputDirection() {}
    public record Query(String target) implements RuleEngine.WorldCommand {
        public Query { if (target == null || target.isBlank()) throw new IllegalArgumentException("Missing input target"); }
    }
    public enum Outcome { OBSERVED, MISSING_TARGET, DEAD, UNLOADED, UNSUPPORTED }
    public record Result(Query query, Outcome outcome, Optional<WorldDirection> direction) implements DirectionResult {
        public Result {
            Objects.requireNonNull(query); Objects.requireNonNull(outcome); Objects.requireNonNull(direction);
            if (outcome != Outcome.OBSERVED && direction.isPresent()) throw new IllegalArgumentException("Unobserved input has a direction");
            if (direction.filter(d -> d.y() != 0).isPresent()) throw new IllegalArgumentException("Movement input direction is horizontal");
        }
        public boolean observed() { return outcome == Outcome.OBSERVED; }
        public boolean neutral() { return observed() && direction.isEmpty(); }
    }
    /** Opposing held keys cancel. Diagonals are normalized once, independently of pitch and current velocity. */
    public static Optional<WorldDirection> held(String dimension, double yawDegrees, boolean forward, boolean backward, boolean left, boolean right) {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Missing input dimension");
        Numbers.finite(yawDegrees, "input yaw");
        int ahead = (forward ? 1 : 0) - (backward ? 1 : 0), side = (left ? 1 : 0) - (right ? 1 : 0);
        if (ahead == 0 && side == 0) return Optional.empty();
        double yaw = Math.toRadians(yawDegrees);
        return Optional.of(new WorldDirection(dimension, side * Math.cos(yaw) - ahead * Math.sin(yaw), 0, ahead * Math.cos(yaw) + side * Math.sin(yaw)));
    }
}
