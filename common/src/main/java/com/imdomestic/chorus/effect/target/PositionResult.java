package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;
import java.util.Optional;

/** A typed position value; a saved value carries no entity or claim that a world read just occurred. */
public interface PositionResult extends RuleEngine.ActionResult {
    Optional<WorldPosition> position();
    record Stored(Optional<WorldPosition> position) implements PositionResult {
        public Stored { Objects.requireNonNull(position); }
    }
}
