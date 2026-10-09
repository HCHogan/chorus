package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

public record DirectionQuery(String target) implements RuleEngine.WorldCommand {
    public DirectionQuery { if (target == null || target.isBlank()) throw new IllegalArgumentException("Missing direction target"); }
    public record Result(DirectionQuery query, Optional<WorldDirection> direction) implements RuleEngine.ActionResult {
        public Result { Objects.requireNonNull(query); Objects.requireNonNull(direction); }
    }
}
