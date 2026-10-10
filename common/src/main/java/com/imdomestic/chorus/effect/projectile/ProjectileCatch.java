package com.imdomestic.chorus.effect.projectile;

import com.imdomestic.chorus.stat.Numbers;

/** Explicit interaction window in this flight's physical age, independent of automatic arrival. */
public record ProjectileCatch(double radius, long opensAtMicros, long closesAtMicros, boolean lineOfSight) {
    public ProjectileCatch {
        Numbers.nonnegative(radius, "catch radius");
        if (opensAtMicros < 0 || closesAtMicros <= opensAtMicros || closesAtMicros == Long.MAX_VALUE)
            throw new IllegalArgumentException("Catch needs a finite nonempty window");
    }
    public boolean allows(long ageMicros, double distance) {
        Numbers.nonnegative(distance, "catch distance");
        return ageMicros >= opensAtMicros && ageMicros < closesAtMicros && distance <= radius;
    }
}
