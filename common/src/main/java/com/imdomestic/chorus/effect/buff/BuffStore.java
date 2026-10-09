package com.imdomestic.chorus.effect.buff;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.Collections;

public record BuffStore(long timeMicros, long nextGeneration, Map<BuffInstance.Key, BuffInstance> instances) {
    public BuffStore {
        if (timeMicros < 0 || timeMicros == BuffDefinition.FOREVER || nextGeneration < 1) throw new IllegalArgumentException("Invalid buff store clock or sequence");
        instances = Collections.unmodifiableMap(new TreeMap<>(instances));
        var generations = new java.util.HashSet<Long>();
        final long clock = timeMicros;
        instances.forEach((key, instance) -> {
            if (!key.equals(instance.key()) || instance.count() == 0 || instance.generation() >= nextGeneration || !generations.add(instance.generation())
                    || (instance.pausedAt().isPresent() && instance.pausedAt().getAsLong() > clock)) {
                throw new IllegalArgumentException("Invalid stored buff instance");
            }
        });
    }
    public static BuffStore empty() { return new BuffStore(0, 1, Map.of()); }
    public Optional<BuffInstance> active(BuffInstance.Key key) {
        return Optional.ofNullable(instances.get(key)).filter(b -> b.activeCount(timeMicros) > 0);
    }
    public long nextDeadline() { return instances.values().stream().mapToLong(BuffInstance::deadline).min().orElse(BuffDefinition.FOREVER); }
}
