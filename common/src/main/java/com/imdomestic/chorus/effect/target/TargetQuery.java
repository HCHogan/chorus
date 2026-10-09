package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.OptionalInt;
import java.util.Comparator;

/** A world observation, followed by an immutable, ordered snapshot of entity identities. */
public record TargetQuery(Center center, TargetShape shape, Relation relation, String relativeTo, boolean includeCenter,
        Set<String> exclude, Order order, OptionalInt limit, Anchor targetAnchor, boolean lineOfSight)
        implements RuleEngine.WorldCommand {
    public enum Relation { ANY, ALLIED, NOT_ALLIED }
    public enum Order { IDENTITY, NEAREST }
    public enum Outcome { AVAILABLE, MISSING_CENTER, MISSING_RELATIVE, MISSING_DIRECTION, WRONG_DIMENSION }
    public enum Anchor { FEET, BODY, EYES }
    public sealed interface Center permits EntityCenter, PositionCenter {}
    public record EntityCenter(String entity, Anchor anchor) implements Center {
        public EntityCenter { reference(entity); Objects.requireNonNull(anchor); }
        public EntityCenter(String entity) { this(entity, Anchor.FEET); }
    }
    /** Empty capture stays absent; a query cannot silently substitute the world origin. */
    public record PositionCenter(java.util.Optional<WorldPosition> position) implements Center {
        public PositionCenter { Objects.requireNonNull(position); }
        public PositionCenter(WorldPosition position) { this(java.util.Optional.of(position)); }
    }
    public TargetQuery {
        Objects.requireNonNull(center); reference(relativeTo); Objects.requireNonNull(shape); Objects.requireNonNull(targetAnchor); Objects.requireNonNull(relation);
        exclude = Set.copyOf(exclude); exclude.forEach(TargetQuery::reference); Objects.requireNonNull(order); Objects.requireNonNull(limit);
        if (limit.isPresent() && limit.getAsInt() < 0) throw new IllegalArgumentException("Negative target limit");
    }
    public TargetQuery(Center center, double radius, Relation relation, String relativeTo, boolean includeCenter, Set<String> exclude, Order order, OptionalInt limit) {
        this(center, new TargetShape.Sphere(radius), relation, relativeTo, includeCenter, exclude, order, limit, Anchor.FEET, false);
    }
    /** Legacy spherical API; for other shapes this is the enclosing radius, not their axial length. */
    public double radius() { return shape.boundingRadius(); }
    public TargetQuery(String center, double radius, Relation relation, String relativeTo, boolean includeCenter) {
        this(new EntityCenter(center), radius, relation, relativeTo, includeCenter, Set.of(), Order.IDENTITY, OptionalInt.empty());
    }
    public TargetQuery(String center, double radius, Relation relation, String relativeTo, boolean includeCenter,
            Set<String> exclude, Order order, OptionalInt limit) {
        this(new EntityCenter(center), radius, relation, relativeTo, includeCenter, exclude, order, limit);
    }
    public TargetQuery(Center center, double radius, Relation relation, String relativeTo, boolean includeCenter) {
        this(center, radius, relation, relativeTo, includeCenter, Set.of(), Order.IDENTITY, OptionalInt.empty());
    }
    public Comparator<Target> comparator() {
        Comparator<Target> identity = Comparator.comparing(Target::entity);
        return order == Order.NEAREST ? Comparator.comparingDouble(Target::distance).thenComparing(identity) : identity;
    }
    private static void reference(String id) { if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing entity reference"); }
    /** Distance is captured by the query; evaluating a result never performs another world read. */
    public record Target(String entity, double distance, java.util.Optional<TargetShape.Offset> offset) implements Targets.Reference {
        public Target { reference(entity); Numbers.nonnegative(distance, "target distance"); Objects.requireNonNull(offset);
            if (offset.isPresent() && Double.compare(distance, offset.orElseThrow().distance()) != 0) throw new IllegalArgumentException("Target distance differs from sampled offset");
        }
        public Target(String entity, double distance) { this(entity, distance, java.util.Optional.empty()); }
        public Target(String entity, TargetShape.Offset offset) { this(entity, offset.distance(), java.util.Optional.of(offset)); }
    }
    public record Result(TargetQuery query, Outcome outcome, List<Target> targets) implements Targets.Collection {
        public Result {
            Objects.requireNonNull(query); Objects.requireNonNull(outcome); targets = List.copyOf(targets);
            if (outcome != Outcome.AVAILABLE && !targets.isEmpty()) throw new IllegalArgumentException("Unavailable query contains targets");
            if (outcome == Outcome.AVAILABLE && query.shape() instanceof TargetShape.Cone cone && cone.direction().isEmpty()) throw new IllegalArgumentException("Cone result cannot be available without direction");
            // Limits apply after filtering and ordering.
            if (query.limit().isPresent() && targets.size() > query.limit().getAsInt()) throw new IllegalArgumentException("Target result exceeds requested limit");
            Target previous = null; var seen = new java.util.HashSet<String>();
            for (var target : targets) {
                if (!seen.add(target.entity()) || previous != null && query.comparator().compare(previous, target) >= 0) throw new IllegalArgumentException("Query targets must be unique and in requested order");
                if (!query.includeCenter() && query.center() instanceof EntityCenter center && center.entity().equals(target.entity())) throw new IllegalArgumentException("Excluded center in query result");
                if (query.exclude().contains(target.entity())) throw new IllegalArgumentException("Excluded target in query result");
                if (!(query.shape() instanceof TargetShape.Sphere) && target.offset().isEmpty()) throw new IllegalArgumentException("Non-spherical result needs a sampled offset");
                if (target.offset().isPresent() && !query.shape().contains(target.offset().orElseThrow())) throw new IllegalArgumentException("Target outside query shape");
                if (target.distance() > query.radius()) throw new IllegalArgumentException("Target outside query radius");
                previous = target;
            }
        }
    }
}
