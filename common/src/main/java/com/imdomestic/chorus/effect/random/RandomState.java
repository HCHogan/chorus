package com.imdomestic.chorus.effect.random;

import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import java.util.Objects;

/** Immutable, versioned SplitMix64 stream. Only explicit actions advance it; pure queries never draw. */
public record RandomState(long seed, long cursor) {
    public static final String ALGORITHM = "chorus:splitmix64_v1";
    public static final RandomState ZERO = new RandomState(0, 0);
    private static final long GAMMA = 0x9e3779b97f4a7c15L;
    public RandomState { if (cursor < 0) throw new IllegalArgumentException("Negative random cursor"); }
    public enum Distribution { UNIFORM_REAL, UNIFORM_INTEGER }
    public record Draw(RandomState after, long bits) {}
    /** Counter arithmetic is checked; seed/mixer arithmetic intentionally wraps modulo 2^64. */
    public Draw next() {
        long index = Math.incrementExact(cursor);
        long word = seed + GAMMA * index;
        word = (word ^ (word >>> 30)) * 0xbf58476d1ce4e5b9L;
        word = (word ^ (word >>> 27)) * 0x94d049bb133111ebL;
        return new Draw(new RandomState(seed, index), word ^ (word >>> 31));
    }
    public static void validate(Distribution distribution, Measure lower, Measure upper) {
        Objects.requireNonNull(distribution); Objects.requireNonNull(lower); Objects.requireNonNull(upper);
        if (!lower.unit().equals(upper.unit()) || lower.value() > upper.value() || !Double.isFinite(upper.value() - lower.value()))
            throw new IllegalArgumentException("Invalid random bounds or units");
        if (distribution == Distribution.UNIFORM_INTEGER && (lower.value() < Integer.MIN_VALUE || upper.value() > Integer.MAX_VALUE
                || lower.value() != Math.rint(lower.value()) || upper.value() != Math.rint(upper.value())))
            throw new IllegalArgumentException("Integer random bounds must fit signed 32-bit integers");
    }
    /** The full receipt can be recorded or retained across a continuation and verified without changing state. */
    public record Sample(Distribution distribution, RandomState before, RandomState after, Measure lower, Measure upper, Measure value) implements RuleEngine.ActionResult {
        public Sample {
            Objects.requireNonNull(before); Objects.requireNonNull(after); Objects.requireNonNull(value); validate(distribution, lower, upper);
            var expected = resolve(before, distribution, lower, upper);
            if (!after.equals(expected.after()) || !value.equals(expected.value())) throw new IllegalArgumentException("Random receipt does not match its seed, cursor and bounds");
        }
        public String algorithm() { return ALGORITHM; }
        public long draws() { return after.cursor() - before.cursor(); }
    }
    private record Resolved(RandomState after, Measure value) {}
    private static Resolved resolve(RandomState before, Distribution distribution, Measure lower, Measure upper) {
        var draw = before.next(); double value;
        if (distribution == Distribution.UNIFORM_REAL) {
            double fraction = (draw.bits() >>> 11) * 0x1.0p-53;
            value = lower.value() + (upper.value() - lower.value()) * fraction;
            // Rounding must not turn the half-open upper boundary into an extra outcome.
            if (lower.value() != upper.value() && value >= upper.value()) value = Math.nextDown(upper.value());
        } else {
            long width = (long) upper.value() - (long) lower.value() + 1;
            long span = 1L << 32, limit = span - span % width;
            long candidate = draw.bits() >>> 32;
            while (candidate >= limit) { draw = draw.after().next(); candidate = draw.bits() >>> 32; }
            value = (long) lower.value() + candidate % width;
        }
        return new Resolved(draw.after(), new Measure(value, lower.unit()));
    }
    public Sample sample(Distribution distribution, Measure lower, Measure upper) {
        validate(distribution, lower, upper); var resolved = resolve(this, distribution, lower, upper);
        return new Sample(distribution, this, resolved.after(), lower, upper, resolved.value());
    }
}
