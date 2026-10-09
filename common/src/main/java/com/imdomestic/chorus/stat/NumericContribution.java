package com.imdomestic.chorus.stat;

import java.util.Objects;

/** A resolved contribution. Conditions and world reads belong to the caller, not stat. */
public record NumericContribution(String id, String stage, String group, Operation operation,
        Measure amount, String percentOf, String stackingKey, Source source, int priority) {
    public enum Operation { ADD, BASE_PERCENT, MULTIPLY, RESIST, REPLACE }
    public enum Confidence { OFFICIAL, MEASURED, FITTED, ASSUMED, CONFLICT }

    public record Source(String definition, String instance, String reference, Confidence confidence, String version) {
        public Source {
            Objects.requireNonNull(definition);
            Objects.requireNonNull(instance);
            Objects.requireNonNull(reference);
            Objects.requireNonNull(confidence);
            Objects.requireNonNull(version);
        }
        public Source(String definition, String instance, String reference, Confidence confidence) { this(definition, instance, reference, confidence, ""); }
    }

    public NumericContribution {
        Objects.requireNonNull(id);
        Objects.requireNonNull(stage);
        Objects.requireNonNull(group);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(percentOf);
        Objects.requireNonNull(stackingKey);
        Objects.requireNonNull(source);
        if (id.isBlank() || stage.isBlank() || group.isBlank() || stackingKey.isBlank()) {
            throw new IllegalArgumentException("Contribution identity, stage, group and family must not be blank");
        }
        if ((operation == Operation.BASE_PERCENT) != !percentOf.isEmpty()) {
            throw new IllegalArgumentException("Only base_percent requires a percent_of stage");
        }
        if (operation == Operation.RESIST) Numbers.fraction(amount.value(), "resistance");
        if (operation == Operation.MULTIPLY && amount.value() < -1) {
            throw new IllegalArgumentException("A multiplier delta cannot be below -1");
        }
    }
}
