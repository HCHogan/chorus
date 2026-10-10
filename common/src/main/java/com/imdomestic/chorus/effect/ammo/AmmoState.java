package com.imdomestic.chorus.effect.ammo;

import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** Discrete ammunition owned by a stable weapon instance. Overflow does not rewrite base magazine size. */
public record AmmoState(String weapon, int magazine, int capacity, Optional<Reserve> reserve) {
    public record Reserve(int rounds, int capacity) {
        public Reserve { if (rounds < 0 || capacity < rounds) throw new IllegalArgumentException("Invalid ammunition reserve"); }
    }
    public AmmoState {
        if (weapon == null || weapon.isBlank() || magazine < 0 || capacity < 1) throw new IllegalArgumentException("Invalid ammunition account");
        Objects.requireNonNull(reserve); // Empty means unlimited, never an unknown or zero finite reserve.
    }
    public AmmoState magazine(int rounds) { return new AmmoState(weapon, rounds, capacity, reserve); }
    public AmmoState reserve(int rounds) { return new AmmoState(weapon, magazine, capacity, Optional.of(new Reserve(rounds, reserve.orElseThrow().capacity()))); }
    public enum Field { MAGAZINE, CAPACITY, MISSING, RESERVES, RESERVE_CAPACITY }
    public int read(Field field) {
        return switch (field) {
            case MAGAZINE -> magazine; case CAPACITY -> capacity; case MISSING -> Math.max(0, capacity - magazine);
            case RESERVES -> reserve.orElseThrow(() -> new IllegalArgumentException("Unlimited reserve has no finite amount")).rounds();
            case RESERVE_CAPACITY -> reserve.orElseThrow(() -> new IllegalArgumentException("Unlimited reserve has no finite capacity")).capacity();
        };
    }
    public static int rounds(double value) {
        Numbers.nonnegative(value, "ammunition rounds");
        if (value > Integer.MAX_VALUE || value != Math.rint(value)) throw new IllegalArgumentException("Expected integral rounds; round explicitly before an ammunition action");
        return (int) value;
    }
}
