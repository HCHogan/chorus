package com.imdomestic.chorus.effect.buff;

import java.util.Objects;
import java.util.Set;

/** Resolved lifetime policy. Value expressions are evaluated before entering the buff service. */
public record BuffDefinition(String id, String version, int maximumStacks, Timer timer, Binding binding,
        StackMode stackMode, boolean keepHighestTier, boolean creditOverflow, Set<String> tags, BuffSchema components) {
    public enum TimerMode { SHARED, PER_STACK }
    public enum Decay { ALL, ONE_BY_ONE }
    public enum Refresh { NONE, RESET, EXTEND, HISTORIC_MAX, MAX_REMAINING }
    public enum StackMode { ADD, MAX, REPLACE }
    public enum Attach { HOLDER, WEAPON, TARGET }
    public enum InstanceBy { NONE, SOURCE, WEAPON }
    public enum Affects { ALL, INSTANCE_WEAPON, ABILITY }
    public enum OnStow { KEEP, REMOVE, PAUSE }
    public static final long FOREVER = Long.MAX_VALUE;

    public record Timer(long durationMicros, TimerMode mode, Decay decay, long decayIntervalMicros,
            Refresh refresh, long extensionCapMicros, long pauseRoundingMicros) {
        public Timer {
            Objects.requireNonNull(mode); Objects.requireNonNull(decay); Objects.requireNonNull(refresh);
            if (durationMicros <= 0 || extensionCapMicros <= 0 || pauseRoundingMicros < 0 || pauseRoundingMicros == FOREVER) {
                throw new IllegalArgumentException("Invalid buff timer duration/cap/rounding");
            }
            if (decay == Decay.ONE_BY_ONE && (mode != TimerMode.SHARED || decayIntervalMicros <= 0 || decayIntervalMicros == FOREVER)) {
                throw new IllegalArgumentException("Sequential decay requires a shared timer and finite positive interval");
            }
            if (decay == Decay.ALL && decayIntervalMicros != 0) throw new IllegalArgumentException("ALL decay has no interval");
            if (mode == TimerMode.PER_STACK && refresh != Refresh.NONE) {
                throw new IllegalArgumentException("Per-stack lifetimes do not refresh other stacks");
            }
        }
    }
    public record Binding(Attach attach, InstanceBy instancedBy, Affects affects, OnStow onStow) {
        public Binding { Objects.requireNonNull(attach); Objects.requireNonNull(instancedBy); Objects.requireNonNull(affects); Objects.requireNonNull(onStow); }
    }

    public BuffDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(version); Objects.requireNonNull(timer);
        Objects.requireNonNull(binding); Objects.requireNonNull(stackMode); Objects.requireNonNull(components); tags = Set.copyOf(tags);
        if (id.isBlank() || version.isBlank() || maximumStacks < 1) throw new IllegalArgumentException("Invalid buff definition identity or maximum stacks");
        if (timer.mode() == TimerMode.PER_STACK && stackMode != StackMode.ADD) {
            throw new IllegalArgumentException("Per-stack lifetimes require additive stack applications");
        }
    }
    public BuffDefinition(String id, String version, int maximumStacks, Timer timer, Binding binding,
            StackMode stackMode, boolean keepHighestTier, boolean creditOverflow, Set<String> tags) {
        this(id, version, maximumStacks, timer, binding, stackMode, keepHighestTier, creditOverflow, tags, BuffSchema.EMPTY);
    }
}
