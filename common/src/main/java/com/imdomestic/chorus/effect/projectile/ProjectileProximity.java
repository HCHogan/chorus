package com.imdomestic.chorus.effect.projectile;

import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;
import java.util.OptionalDouble;

/** A spherical fuse around a live target anchor, armed by actual cumulative flight distance. */
public record ProjectileProximity(double radius, double armDistance, TargetQuery.Anchor anchor,
        TargetQuery.Relation relation, boolean lineOfSight) {
    public ProjectileProximity {
        Numbers.nonnegative(radius, "proximity radius"); Numbers.nonnegative(armDistance, "proximity arming distance");
        Objects.requireNonNull(anchor); Objects.requireNonNull(relation);
        if (radius == 0) throw new IllegalArgumentException("Proximity radius must be positive");
    }
    /** First eligible entry, including arming while already inside the sphere. */
    public OptionalDouble entry(WorldPosition from, WorldPosition to, WorldPosition center, double traveled) {
        Numbers.nonnegative(traveled, "projectile traveled distance");
        if (!from.dimension().equals(to.dimension()) || !from.dimension().equals(center.dimension())) throw new IllegalArgumentException("Proximity geometry changed dimension");
        double length = from.distanceTo(to), unarmed = Math.max(0, armDistance - traveled);
        if (unarmed > length) return OptionalDouble.empty();
        double start = length == 0 ? 0 : unarmed / length;
        var armed = new WorldPosition(from.dimension(), from.x() + (to.x() - from.x()) * start,
                from.y() + (to.y() - from.y()) * start, from.z() + (to.z() - from.z()) * start);
        var entry = ProjectileDestination.sphereEntry(armed, to, center, radius);
        return entry.isEmpty() ? entry : OptionalDouble.of(start + (1 - start) * entry.getAsDouble());
    }
}
