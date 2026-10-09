package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.stat.Numbers;

/** Immutable coordinates in one world. No entity reference, chunk handle or implicit world lookup. */
public record WorldPosition(String dimension, double x, double y, double z) {
    public WorldPosition {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Missing position dimension");
        Numbers.finite(x, "position x"); Numbers.finite(y, "position y"); Numbers.finite(z, "position z");
    }
}
