package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;
import java.util.Optional;

/** A world-scoped reference to one entity life, with an explicit observed spatial anchor. */
public final class EntityAnchor {
    private EntityAnchor() {}
    public record Reference(String target, String life, String dimension) {
        public Reference { require(target); require(life); require(dimension); }
        private static void require(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing entity life reference field");
        }
    }
    public record Query(String target, TargetQuery.Anchor anchor, Optional<Reference> expected) implements RuleEngine.WorldCommand {
        public Query {
            Reference.require(target); Objects.requireNonNull(anchor); Objects.requireNonNull(expected);
            if (expected.isPresent() && !expected.orElseThrow().target().equals(target)) throw new IllegalArgumentException("Foreign entity life target");
        }
    }
    public record Observation(Reference reference, WorldPosition position, TargetQuery.Anchor anchor, boolean alive) {
        public Observation {
            Objects.requireNonNull(reference); Objects.requireNonNull(position); Objects.requireNonNull(anchor);
            if (!reference.dimension().equals(position.dimension())) throw new IllegalArgumentException("Entity life and position dimensions differ");
        }
    }
    public enum Outcome { AVAILABLE, MISSING, REPLACED, WRONG_DIMENSION }
    public record Result(Query query, Outcome outcome, Optional<Observation> observation) implements PositionResult {
        public Result {
            Objects.requireNonNull(query); Objects.requireNonNull(outcome); Objects.requireNonNull(observation);
            if ((outcome == Outcome.AVAILABLE) != observation.isPresent()) throw new IllegalArgumentException("Entity anchor outcome and observation differ");
            observation.ifPresent(o -> {
                if (!o.reference().target().equals(query.target()) || o.anchor() != query.anchor()
                        || query.expected().filter(r -> !r.equals(o.reference())).isPresent())
                    throw new IllegalArgumentException("Entity anchor differs from requested life or anchor");
            });
            if (outcome == Outcome.REPLACED && query.expected().isEmpty()) throw new IllegalArgumentException("Replacement requires an expected life");
        }
        @Override public Optional<WorldPosition> position() { return observation.map(Observation::position); }
        public Observation requireObservation() { return observation.orElseThrow(() -> new IllegalArgumentException("Entity anchor unavailable: " + outcome)); }
    }
    /** Compare the life at the actual write boundary, not merely at an earlier observation. */
    public record GuardedDamage(Reference reference, DamageCommand damage) implements RuleEngine.WorldCommand {
        public GuardedDamage {
            Objects.requireNonNull(reference); Objects.requireNonNull(damage);
            if (!reference.target().equals(damage.target())) throw new IllegalArgumentException("Guarded damage target differs from entity life");
        }
    }
}
