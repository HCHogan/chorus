package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

public record DirectionQuery(String target, Mode mode) implements RuleEngine.WorldCommand {
    public enum Mode { LOOK, HORIZONTAL }
    public DirectionQuery(String target) { this(target, Mode.LOOK); }
    public DirectionQuery {
        if (target == null || target.isBlank()) throw new IllegalArgumentException("Missing direction target");
        Objects.requireNonNull(mode);
    }
    public record Result(DirectionQuery query, Optional<WorldDirection> direction) implements DirectionResult {
        public Result {
            Objects.requireNonNull(query); Objects.requireNonNull(direction);
            if (query.mode() == Mode.HORIZONTAL && direction.filter(d -> d.y() != 0).isPresent())
                throw new IllegalArgumentException("Horizontal direction receipt has a vertical component");
        }
    }
}
