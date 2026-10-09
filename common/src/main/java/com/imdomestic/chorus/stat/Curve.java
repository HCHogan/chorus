package com.imdomestic.chorus.stat;

import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Pure curves; additional verified formula types can implement this interface. */
public interface Curve {
    double evaluate(double input);

    enum Interpolation { EXACT, FLOOR, LINEAR }
    enum Boundary { ERROR, CLAMP }

    record Table(NavigableMap<Double, Double> points, Interpolation interpolation, Boundary boundary) implements Curve {
        public Table {
            Objects.requireNonNull(interpolation);
            Objects.requireNonNull(boundary);
            if (points.isEmpty()) throw new IllegalArgumentException("Curve has no points");
            points.forEach((x, y) -> { Numbers.finite(x, "curve x"); Numbers.finite(y, "curve y"); });
            points = Collections.unmodifiableNavigableMap(new TreeMap<>(points));
        }

        @Override public double evaluate(double input) {
            Numbers.finite(input, "curve input");
            if (input < points.firstKey() || input > points.lastKey()) {
                if (boundary == Boundary.ERROR) throw new IllegalArgumentException("Input outside curve: " + input);
                input = Math.clamp(input, points.firstKey(), points.lastKey());
            }
            Double exact = points.get(input);
            if (exact != null) return exact;
            if (interpolation == Interpolation.EXACT) throw new IllegalArgumentException("Missing curve point: " + input);
            var lower = points.floorEntry(input);
            if (interpolation == Interpolation.FLOOR) return lower.getValue();
            var upper = points.ceilingEntry(input);
            double t = (input - lower.getKey()) / (upper.getKey() - lower.getKey());
            return Numbers.finite(lower.getValue() * (1 - t) + upper.getValue() * t, "interpolated curve output");
        }
    }

    /** Coefficients are ordered from constant to highest power. Domain is explicit. */
    record Polynomial(List<Double> coefficients, double minimum, double maximum, Boundary boundary) implements Curve {
        public Polynomial {
            coefficients = List.copyOf(coefficients);
            Objects.requireNonNull(boundary);
            if (coefficients.isEmpty()) throw new IllegalArgumentException("Polynomial has no coefficients");
            coefficients.forEach(c -> Numbers.finite(c, "coefficient"));
            Numbers.finite(minimum, "minimum");
            Numbers.finite(maximum, "maximum");
            if (minimum > maximum) throw new IllegalArgumentException("Inverted polynomial domain");
        }

        @Override public double evaluate(double input) {
            Numbers.finite(input, "curve input");
            if (boundary == Boundary.ERROR && (input < minimum || input > maximum)) {
                throw new IllegalArgumentException("Input outside polynomial domain: " + input);
            }
            input = Math.clamp(input, minimum, maximum);
            double result = 0;
            for (int i = coefficients.size() - 1; i >= 0; i--) result = result * input + coefficients.get(i);
            return Numbers.finite(result, "polynomial output");
        }
    }
}
