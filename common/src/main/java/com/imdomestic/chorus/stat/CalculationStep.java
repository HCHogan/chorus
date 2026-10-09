package com.imdomestic.chorus.stat;

import java.util.Objects;

import com.imdomestic.chorus.stat.NumericContribution.Operation;

public sealed interface CalculationStep {
    String id();

    record Apply(String id, Operation operation, NumericGroup group, String percentOf, String factor) implements CalculationStep {
        public Apply {
            Objects.requireNonNull(id);
            Objects.requireNonNull(operation);
            Objects.requireNonNull(group);
            Objects.requireNonNull(percentOf);
            Objects.requireNonNull(factor);
            if (!factor.isEmpty() && (operation != Operation.MULTIPLY || !factor.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
                throw new IllegalArgumentException("A named factor must be a namespaced multiply stage");
            }
            if ((operation == Operation.BASE_PERCENT) != !percentOf.isEmpty()) {
                throw new IllegalArgumentException("Only base_percent steps require percent_of");
            }
        }
        public Apply(String id, Operation operation, NumericGroup group, String percentOf) { this(id, operation, group, percentOf, ""); }
        public Apply(String id, Operation operation, NumericGroup group) { this(id, operation, group, ""); }
    }

    record Clamp(String id, double minimum, double maximum) implements CalculationStep {
        public Clamp {
            Objects.requireNonNull(id);
            Numbers.finite(minimum, "minimum");
            Numbers.finite(maximum, "maximum");
            if (minimum > maximum) throw new IllegalArgumentException("Inverted clamp bounds");
        }
    }

    record Transform(String id, Curve curve, Unit outputUnit) implements CalculationStep {
        public Transform {
            Objects.requireNonNull(id);
            Objects.requireNonNull(curve);
            Objects.requireNonNull(outputUnit);
        }
    }

    enum Rounding { FLOOR, CEIL, NEAREST_EVEN }
    record Round(String id, Rounding rounding) implements CalculationStep {
        public Round { Objects.requireNonNull(id); Objects.requireNonNull(rounding); }
    }
}
