package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Entity identities can survive a query without pretending to retain current spatial measurements. */
public final class Targets {
    private Targets() {}
    public interface Reference extends RuleEngine.ActionResult { String entity(); }
    public interface Collection extends RuleEngine.ActionResult { List<? extends Reference> targets(); }
    public record Identity(String entity) implements Reference {
        public Identity { if (entity == null || entity.isBlank()) throw new IllegalArgumentException("Missing target identity"); }
    }
    /** A canonical identity set. There is deliberately no distance field. */
    public record Identities(List<Identity> targets) implements Collection {
        public static final Identities EMPTY = new Identities(List.of());
        public Identities {
            targets = List.copyOf(targets); String previous = null;
            for (var target : targets) {
                if (previous != null && previous.compareTo(target.entity()) >= 0) throw new IllegalArgumentException("Target identities must be unique and sorted");
                previous = target.entity();
            }
        }
        public static Identities of(java.util.Collection<String> values) {
            return new Identities(new TreeSet<>(values).stream().map(Identity::new).toList());
        }
        public Set<String> ids() { return targets.stream().map(Identity::entity).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    }
    public enum Part { BEFORE, AFTER, ENTERED, EXITED }
    /** Unavailable observations leave the previous set intact; absence is not an observed empty set. */
    public record Difference(boolean observed, Identities before, Identities after) implements RuleEngine.ActionResult {
        public Difference {
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (!observed && !before.equals(after)) throw new IllegalArgumentException("Unobserved targets cannot change membership");
        }
        public boolean changed() { return !before.equals(after); }
        public Identities part(Part part) {
            return switch (part) {
                case BEFORE -> before; case AFTER -> after;
                case ENTERED -> subtract(after, before); case EXITED -> subtract(before, after);
            };
        }
        private static Identities subtract(Identities left, Identities right) {
            var excluded = right.ids(); return new Identities(left.targets().stream().filter(t -> !excluded.contains(t.entity())).toList());
        }
    }
}
