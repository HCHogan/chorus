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
}
