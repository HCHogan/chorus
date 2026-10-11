package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.stat.Numbers;

/** Immutable coordinates in one world. No entity reference, chunk handle or implicit world lookup. */
public record WorldPosition(String dimension, double x, double y, double z) {
    public WorldPosition {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Missing position dimension");
        Numbers.finite(x, "position x"); Numbers.finite(y, "position y"); Numbers.finite(z, "position z");
    }
    /** Straight-line separation of two observations, never a new entity/world lookup or flight path length. */
    public double distanceTo(WorldPosition other) {
        java.util.Objects.requireNonNull(other);
        if (!dimension.equals(other.dimension)) throw new IllegalArgumentException("Cannot measure distance across dimensions");
        double distance = Math.hypot(Math.hypot(x - other.x, y - other.y), z - other.z);
        Numbers.nonnegative(distance, "position distance");
        return distance;
    }
    /** Translate a saved point by the movement between two observations of its reference anchor. */
    public WorldPosition rebase(WorldPosition from, WorldPosition to) {
        java.util.Objects.requireNonNull(from); java.util.Objects.requireNonNull(to);
        if (!dimension.equals(from.dimension) || !dimension.equals(to.dimension))
            throw new IllegalArgumentException("Cannot rebase positions across dimensions");
        return new WorldPosition(dimension, x + (to.x - from.x), y + (to.y - from.y), z + (to.z - from.z));
    }
}
