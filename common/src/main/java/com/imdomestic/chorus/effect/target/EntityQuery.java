package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One explicit world observation. Missing is distinct from dead; later expressions never reread the entity. */
public record EntityQuery(String target) implements RuleEngine.WorldCommand {
    public EntityQuery { if (target == null || target.isBlank()) throw new IllegalArgumentException("Missing entity query target"); }

    public enum TagSource { ENTITY, TYPE }

    /** Native posture and movement flags at observation time, not measured displacement or input intent. */
    public record Movement(boolean onGround, boolean sprinting, boolean crouching, boolean swimming,
            boolean fallFlying, boolean passenger, boolean sleeping) {}

    /** Native observations only; entity tags and registry type tags have separate namespaces. */
    public record View(boolean alive, boolean player, double health, double maximumHealth, double absorption,
            Set<String> entityTags, Set<String> typeTags, Optional<Movement> movement) {
        public View {
            Numbers.nonnegative(health, "observed health"); Numbers.nonnegative(absorption, "observed absorption");
            Numbers.nonnegative(maximumHealth, "observed maximum health");
            if (maximumHealth == 0) throw new IllegalArgumentException("Observed maximum health must be positive");
            entityTags = Set.copyOf(entityTags); typeTags = Set.copyOf(typeTags); Objects.requireNonNull(movement);
        }
        public View(boolean alive, boolean player, double health, double maximumHealth, double absorption,
                Set<String> entityTags, Set<String> typeTags) {
            this(alive, player, health, maximumHealth, absorption, entityTags, typeTags, Optional.empty());
        }
        public View(boolean alive, boolean player, double health, double maximumHealth, double absorption) {
            this(alive, player, health, maximumHealth, absorption, Set.of(), Set.of());
        }
        public boolean hasTag(TagSource source, String tag) {
            return (switch (source) { case ENTITY -> entityTags; case TYPE -> typeTags; }).contains(tag);
        }
        public Movement observedMovement() { return movement.orElseThrow(() -> new IllegalStateException("Entity movement was not observed")); }
    }
    public record Result(EntityQuery query, Optional<View> view) implements RuleEngine.ActionResult {
        public Result { Objects.requireNonNull(query); Objects.requireNonNull(view); }
        public View available() { return view.orElseThrow(() -> new IllegalStateException("Entity observation is unavailable: " + query.target())); }
    }
}
