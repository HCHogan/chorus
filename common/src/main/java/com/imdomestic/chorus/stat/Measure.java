package com.imdomestic.chorus.stat;

import java.util.Objects;

public record Measure(double value, Unit unit) {
    public Measure {
        Numbers.finite(value, "measure");
        Objects.requireNonNull(unit, "unit");
    }
}
