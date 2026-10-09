package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.Map;

/** Explicit per-impact measurements, separate from both frozen attack metadata and actual result measurements. */
public record ImpactData(Map<String, Measure> numbers) {
    public static final ImpactData EMPTY = new ImpactData(Map.of());
    public ImpactData { numbers = Map.copyOf(numbers); numbers.keySet().forEach(ImpactData::requireName); }
    public static void requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Missing impact measurement name");
    }
    public Measure number(String name, Unit unit) {
        var value = numbers.get(name);
        if (value == null) throw new IllegalArgumentException("Missing impact measurement: " + name);
        if (!value.unit().equals(unit)) throw new IllegalArgumentException("Wrong impact measurement unit for " + name + ": expected " + unit.id());
        return value;
    }
}
