package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;

/** Sequential energy account. One ability charge is 1, regardless of capacity. */
public record ResourceState(Key key, double value, double capacity, long timeMicros) {
    public record Key(String holder, String resource) {
        public Key { Objects.requireNonNull(holder); Objects.requireNonNull(resource); }
    }
    public ResourceState {
        Objects.requireNonNull(key);
        Numbers.nonnegative(capacity, "resource capacity");
        Numbers.nonnegative(value, "resource value");
        if (value > capacity) throw new IllegalArgumentException("Resource exceeds capacity");
        if (timeMicros < 0) throw new IllegalArgumentException("Negative logical time");
    }
}
