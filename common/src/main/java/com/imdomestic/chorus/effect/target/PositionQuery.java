package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;
import java.util.Optional;

/** Explicit world read. A dead but still present entity can supply its position; an absent one cannot. */
public record PositionQuery(String target, TargetQuery.Anchor anchor) implements RuleEngine.WorldCommand {
    public PositionQuery(String target) { this(target, TargetQuery.Anchor.FEET); }
    public PositionQuery {
        Objects.requireNonNull(anchor);
        if (target == null || target.isBlank()) throw new IllegalArgumentException("Missing position target");
    }
    public record Result(PositionQuery query, Optional<WorldPosition> position) implements PositionResult {
        public Result { Objects.requireNonNull(query); Objects.requireNonNull(position); }
    }
}
