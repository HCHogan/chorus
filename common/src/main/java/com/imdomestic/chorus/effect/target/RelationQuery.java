package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Explicit current allegiance observation. Unknown is neither allied nor hostile. */
public record RelationQuery(String left, String right) implements RuleEngine.WorldCommand {
    public RelationQuery {
        if (left == null || left.isBlank() || right == null || right.isBlank()) throw new IllegalArgumentException("Missing relation identity");
    }
    public record Result(RelationQuery query, Optional<Boolean> allied) implements RuleEngine.ActionResult {
        public Result { Objects.requireNonNull(query); Objects.requireNonNull(allied); }
    }
}
