package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;

/** A completed one-unit cycle credits a separate account. Partial cycle energy is never spendable uses. */
public final class LinkedRecharge {
    private LinkedRecharge() {}

    public record Result(ResourceState progressBefore, ResourceState progressAfter,
            ResourceResult charges, boolean completed) implements RuleEngine.ActionResult {
        public Result {
            Objects.requireNonNull(progressBefore); Objects.requireNonNull(progressAfter); Objects.requireNonNull(charges);
            validate(progressBefore, charges.before());
            boolean ready = progressBefore.value() == 1;
            var expectedProgress = ready ? reset(progressBefore) : progressBefore;
            double requested = ready ? charges.requested() : 0;
            var expectedCharges = Resources.grant(charges.before(), requested, requested);
            if (completed != ready || !progressAfter.equals(expectedProgress) || !charges.equals(expectedCharges))
                throw new IllegalArgumentException("Invalid linked recharge receipt");
        }
    }

    private static void validate(ResourceState progress, ResourceState charges) {
        if (!progress.key().holder().equals(charges.key().holder()) || progress.key().equals(charges.key())
                || progress.timeMicros() != charges.timeMicros() || progress.capacity() != 1 || charges.capacity() <= 0)
            throw new IllegalArgumentException("Linked recharge requires distinct, simultaneous accounts for one holder and a one-unit progress meter");
    }
    private static double missing(ResourceState account) {
        return java.math.BigDecimal.valueOf(account.capacity()).subtract(java.math.BigDecimal.valueOf(account.value())).doubleValue();
    }
    private static ResourceState reset(ResourceState progress) {
        return new ResourceState(progress.key(), 0, 1, progress.timeMicros());
    }

    /** All validation occurs before either account is committed. This conversion is not a refundable cost. */
    public static Result complete(ResourceState progress, ResourceState charges) {
        return complete(progress, charges, missing(charges));
    }
    /** Explicit cycle yield, independent of the meter's gain scaling; overflow is discarded at the current ceiling. */
    public static Result complete(ResourceState progress, ResourceState charges, double amount) {
        validate(progress, charges);
        com.imdomestic.chorus.stat.Numbers.nonnegative(amount, "recharge cycle yield");
        boolean ready = progress.value() == 1;
        double creditedRequest = ready ? amount : 0;
        return new Result(progress, ready ? reset(progress) : progress, Resources.grant(charges, creditedRequest, creditedRequest), ready);
    }
}
