package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;

/** Explicit per-instance configuration; separate from event measurements and attribution. */
public final class EffectParameters {
    private EffectParameters() {}
    public static void name(String name) {
        if (name == null || !name.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid effect parameter name: " + name);
    }
    public static <T> Map<String, T> copy(Map<String, T> values) {
        values.forEach((name, value) -> { name(name); Objects.requireNonNull(value); });
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }
    public static void validate(Map<String, Unit> declaration, Map<String, Measure> values) {
        if (!declaration.keySet().equals(values.keySet())) throw new IllegalArgumentException("Effect parameter names do not match declaration: expected " + declaration.keySet() + ", got " + values.keySet());
        declaration.forEach((name, unit) -> {
            if (!unit.equals(values.get(name).unit())) throw new IllegalArgumentException("Wrong unit for effect parameter: " + name);
        });
    }
}
