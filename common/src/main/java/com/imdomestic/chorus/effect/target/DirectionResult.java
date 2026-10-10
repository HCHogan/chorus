package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** A captured or derived direction; deriving a value does not perform a new world observation. */
public interface DirectionResult extends RuleEngine.ActionResult {
    Optional<WorldDirection> direction();
    record Stored(Optional<WorldDirection> direction) implements DirectionResult {
        public Stored { Objects.requireNonNull(direction); }
    }
}
