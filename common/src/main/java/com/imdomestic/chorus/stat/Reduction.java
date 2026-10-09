package com.imdomestic.chorus.stat;

import java.util.List;
import java.util.OptionalDouble;

/** Empty is absence, including for MAX; a negative contribution is never replaced by zero. */
public enum Reduction {
    SUM, MAX, PRODUCT, RESIST;

    public OptionalDouble reduce(List<Double> values) {
        if (values.isEmpty()) return OptionalDouble.empty();
        double result = switch (this) {
            case SUM -> 0;
            case MAX -> values.getFirst();
            case PRODUCT, RESIST -> 1;
        };
        for (double value : values) {
            Numbers.finite(value, "reduction input");
            if (this == PRODUCT && value < -1) throw new IllegalArgumentException("Product delta below -1");
            if (this == RESIST) Numbers.fraction(value, "resistance");
            result = switch (this) {
                case SUM -> result + value;
                case MAX -> Math.max(result, value);
                case PRODUCT -> result * (1 + value);
                case RESIST -> result * (1 - value);
            };
        }
        result = switch (this) {
            case PRODUCT -> result - 1;
            case RESIST -> 1 - result;
            default -> result;
        };
        return OptionalDouble.of(Numbers.finite(result, "reduction output"));
    }
}
