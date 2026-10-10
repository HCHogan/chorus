package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.stat.Numbers;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

/** Normalize a quoted gain once. Game-specific factors and exemptions belong to content. */
public final class EnergyGains {
    private EnergyGains() {}
    public enum Basis { BASE, REFERENCE, FIXED }
    public record Normalized(Basis basis, double requested, Map<String, Double> referenceFactors, double base) {
        public Normalized { referenceFactors = Collections.unmodifiableMap(new TreeMap<>(referenceFactors)); }
    }
    public static Normalized normalize(Basis basis, double requested, Map<String, Double> factors) {
        Objects.requireNonNull(basis); Numbers.nonnegative(requested, "requested energy");
        if (basis == Basis.REFERENCE ? factors.isEmpty() : !factors.isEmpty()) {
            throw new IllegalArgumentException("Only reference-basis gains require included reference factors");
        }
        BigDecimal divisor = BigDecimal.ONE;
        for (var entry : new TreeMap<>(factors).entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid reference factor id");
            double value = Numbers.finite(entry.getValue(), "reference factor");
            if (value <= 0) throw new IllegalArgumentException("Reference factors must be strictly positive");
            divisor = divisor.multiply(BigDecimal.valueOf(value));
        }
        double base = Numbers.nonnegative(BigDecimal.valueOf(requested).divide(divisor, MathContext.DECIMAL128).doubleValue(), "normalized energy");
        return new Normalized(basis, requested, factors, base);
    }
}
