package com.imdomestic.chorus.effect.object;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** A damageable entity with its own identity, physical dimensions and finite lifetime. */
public final class WorldConstruct {
    private WorldConstruct() {}
    public record Parameters(double health, double width, double height, long lifetimeMicros) {
        public Parameters {
            positive(health, "construct health"); positive(width, "construct width"); positive(height, "construct height");
            if (lifetimeMicros <= 0 || lifetimeMicros == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid construct lifetime");
        }
        private static void positive(double value, String name) {
            Numbers.finite(value, name); if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        }
    }
    public record Spawn(Optional<WorldPosition> position, String kind, BuffInstance.Origin origin,
            Parameters parameters, Set<String> tags, String version) implements RuleEngine.WorldCommand {
        public Spawn {
            Objects.requireNonNull(position); Objects.requireNonNull(kind); Objects.requireNonNull(origin);
            Objects.requireNonNull(parameters); tags = Set.copyOf(tags); Objects.requireNonNull(version);
            if (kind.isBlank() || version.isBlank() || tags.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Invalid construct identity");
        }
    }
    public enum Outcome { SPAWNED, MISSING_POSITION, WRONG_DIMENSION, UNLOADED, OUT_OF_BOUNDS, UNSUPPORTED_PARAMETERS, OBSTRUCTED, REJECTED }
    /** An unsuccessful spawn is an observed empty collection, never a fabricated entity reference. */
    public record Receipt(Spawn spawn, Outcome outcome, Optional<String> entity) implements Targets.Collection {
        public Receipt {
            Objects.requireNonNull(spawn); Objects.requireNonNull(outcome); Objects.requireNonNull(entity);
            if ((outcome == Outcome.SPAWNED) != entity.isPresent() || entity.filter(String::isBlank).isPresent()) throw new IllegalArgumentException("Invalid construct receipt");
            if (outcome == Outcome.SPAWNED && spawn.position().isEmpty()) throw new IllegalArgumentException("Spawned construct has no position");
        }
        @Override public List<Targets.Identity> targets() { return entity.map(id -> List.of(new Targets.Identity(id))).orElse(List.of()); }
        public static Receipt rejected(Spawn spawn, Outcome outcome) { return new Receipt(spawn, outcome, Optional.empty()); }
    }
}
