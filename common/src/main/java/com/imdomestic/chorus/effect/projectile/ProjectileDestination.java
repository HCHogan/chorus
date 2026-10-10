package com.imdomestic.chorus.effect.projectile;

import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Optional;

/** A captured entity identity with a live arrival sphere, independent of nearest-enemy acquisition. */
public record ProjectileDestination(String target, double turnRate, double arrivalRadius, TargetQuery.Anchor anchor, boolean collideEntities, Optional<ProjectileCatch> catching) {
    public ProjectileDestination(String target, double turnRate, double arrivalRadius, TargetQuery.Anchor anchor, boolean collideEntities) {
        this(target, turnRate, arrivalRadius, anchor, collideEntities, Optional.empty());
    }
    public ProjectileDestination {
        Objects.requireNonNull(target); Objects.requireNonNull(anchor); Objects.requireNonNull(catching);
        if (target.isBlank()) throw new IllegalArgumentException("Missing projectile destination identity");
        Numbers.nonnegative(turnRate, "destination turn rate"); Numbers.nonnegative(arrivalRadius, "arrival radius");
    }
    /** First entry on the closed segment, including a start already inside. Does not sample endpoints only. */
    public OptionalDouble entry(WorldPosition from, WorldPosition to, WorldPosition center) {
        if (!from.dimension().equals(to.dimension()) || !from.dimension().equals(center.dimension())) throw new IllegalArgumentException("Arrival geometry changed dimension");
        double x = center.x() - from.x(), y = center.y() - from.y(), z = center.z() - from.z();
        double distance = Math.hypot(Math.hypot(x, y), z);
        if (distance <= arrivalRadius) return OptionalDouble.of(0);
        double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        double length = Math.hypot(Math.hypot(dx, dy), dz);
        if (length == 0) return OptionalDouble.empty();
        dx /= length; dy /= length; dz /= length;
        double along = x * dx + y * dy + z * dz;
        // Cross product avoids cancellation in distance^2 - along^2 for small arrival radii.
        double perpendicular = Math.hypot(Math.hypot(y * dz - z * dy, z * dx - x * dz), x * dy - y * dx);
        if (perpendicular > arrivalRadius) return OptionalDouble.empty();
        double entry = along - Math.sqrt(Math.max(0, (arrivalRadius - perpendicular) * (arrivalRadius + perpendicular)));
        return entry < 0 || entry > length ? OptionalDouble.empty() : OptionalDouble.of(entry / length);
    }
}
