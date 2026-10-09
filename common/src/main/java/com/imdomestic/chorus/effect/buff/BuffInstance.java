package com.imdomestic.chorus.effect.buff;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public record BuffInstance(long generation, Key key, BuffDefinition definition, Origin origin,
        List<Stack> stacks, long nextStackId, int tier, long originalDurationMicros, long longestDurationMicros,
        OptionalLong pausedAt, BuffComponents components) {
    public record Key(String holder, String definition, String instance) implements Comparable<Key> {
        public Key { Objects.requireNonNull(holder); Objects.requireNonNull(definition); Objects.requireNonNull(instance); }
        @Override public int compareTo(Key other) {
            int c = holder.compareTo(other.holder);
            if (c == 0) c = definition.compareTo(other.definition);
            return c == 0 ? instance.compareTo(other.instance) : c;
        }
    }
    public record Origin(String owner, String source, String weapon, String ability, java.util.Set<String> tags) {
        public Origin { Objects.requireNonNull(owner); Objects.requireNonNull(source); Objects.requireNonNull(weapon); Objects.requireNonNull(ability); tags = java.util.Set.copyOf(tags); }
        public Origin(String owner, String source, String weapon, String ability) { this(owner, source, weapon, ability, java.util.Set.of()); }
    }
    public record Stack(long id, long expiresAt, Origin origin) {
        public Stack {
            Objects.requireNonNull(origin);
            if (id < 1 || expiresAt < 1) throw new IllegalArgumentException("Invalid stack identity or deadline");
        }
    }
    public BuffInstance {
        Objects.requireNonNull(key); Objects.requireNonNull(definition); Objects.requireNonNull(origin);
        stacks = List.copyOf(stacks); Objects.requireNonNull(pausedAt); Objects.requireNonNull(components);
        if (generation < 1 || nextStackId < 1 || tier < 1 || originalDurationMicros <= 0 || longestDurationMicros < originalDurationMicros
                || !key.definition().equals(definition.id()) || stacks.size() > definition.maximumStacks()
                || (pausedAt.isPresent() && (pausedAt.getAsLong() < 0 || pausedAt.getAsLong() == BuffDefinition.FOREVER))) {
            throw new IllegalArgumentException("Invalid buff instance");
        }
        var ids = new HashSet<Long>();
        for (var stack : stacks) if (!ids.add(stack.id()) || stack.id() >= nextStackId) throw new IllegalArgumentException("Invalid stack sequence");
        if (definition.timer().mode() == BuffDefinition.TimerMode.SHARED && stacks.stream().map(Stack::expiresAt).distinct().count() > 1) {
            throw new IllegalArgumentException("Shared timer stacks must have the same deadline");
        }
    }
    public int count() { return stacks.size(); }
    public long deadline() {
        if (pausedAt.isPresent()) return BuffDefinition.FOREVER;
        return stacks.stream().mapToLong(Stack::expiresAt).min().orElse(BuffDefinition.FOREVER);
    }
    public int activeCount(long now) {
        long clock = pausedAt.orElse(now);
        return (int) stacks.stream().filter(s -> s.expiresAt() == BuffDefinition.FOREVER || s.expiresAt() > clock).count();
    }
    public boolean affects(String weapon, String ability) {
        return switch (definition.binding().affects()) {
            case ALL -> true;
            case INSTANCE_WEAPON -> !origin.weapon().isEmpty() && origin.weapon().equals(weapon);
            case ABILITY -> !origin.ability().isEmpty() && origin.ability().equals(ability);
        };
    }
    public BuffInstance withComponents(BuffComponents value) {
        return new BuffInstance(generation, key, definition, origin, stacks, nextStackId, tier,
                originalDurationMicros, longestDurationMicros, pausedAt, value);
    }
}
