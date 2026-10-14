package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.stat.Numbers;

/** A captured unit axis in one dimension, independent of subsequent entity rotation. */
public record WorldDirection(String dimension, double x, double y, double z) {
    public WorldDirection {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Missing direction dimension");
        Numbers.finite(x, "direction x"); Numbers.finite(y, "direction y"); Numbers.finite(z, "direction z");
        double length = Numbers.finite(Math.hypot(Math.hypot(x, y), z), "direction length");
        if (length == 0) throw new IllegalArgumentException("Zero direction");
        x /= length; y /= length; z /= length;
    }
    /** Normalized right-handed cross product; parallel or foreign axes have no usable direction. */
    public java.util.Optional<WorldDirection> cross(WorldDirection other) {
        if (!dimension.equals(other.dimension)) return java.util.Optional.empty();
        double a = y * other.z - z * other.y, b = z * other.x - x * other.z, c = x * other.y - y * other.x;
        return a == 0 && b == 0 && c == 0 ? java.util.Optional.empty() : java.util.Optional.of(new WorldDirection(dimension, a, b, c));
    }
    /** Rodrigues rotation; positive degrees follow the right-hand rule around the captured unit axis. */
    public java.util.Optional<WorldDirection> rotate(WorldDirection axis, double degrees) {
        Numbers.finite(degrees, "direction angle");
        if (!dimension.equals(axis.dimension)) return java.util.Optional.empty();
        double radians = Math.toRadians(degrees % 360), cosine = Math.cos(radians), sine = Math.sin(radians);
        double dot = axis.x * x + axis.y * y + axis.z * z, parallel = dot * (1 - cosine);
        return java.util.Optional.of(new WorldDirection(dimension,
                x * cosine + (axis.y * z - axis.z * y) * sine + axis.x * parallel,
                y * cosine + (axis.z * x - axis.x * z) * sine + axis.y * parallel,
                z * cosine + (axis.x * y - axis.y * x) * sine + axis.z * parallel));
    }
}
