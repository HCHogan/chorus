package com.imdomestic.chorus.stat;

public final class Numbers {
    private Numbers() {}

    public static double finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite: " + value);
        return value;
    }

    public static double nonnegative(double value, String name) {
        finite(value, name);
        if (value < 0) throw new IllegalArgumentException(name + " must be nonnegative: " + value);
        return value;
    }

    public static double fraction(double value, String name) {
        nonnegative(value, name);
        if (value > 1) throw new IllegalArgumentException(name + " must be at most 1: " + value);
        return value;
    }
}
