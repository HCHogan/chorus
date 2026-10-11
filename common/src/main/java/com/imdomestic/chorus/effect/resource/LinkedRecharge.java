package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;

/** A completed one-unit cycle fills a separate account. Partial cycle energy is never spendable uses. */
public final class LinkedRecharge {
    private LinkedRecharge() {}

    public record Result(ResourceState progressBefore, ResourceState progressAfter,
            ResourceResult charges, boolean completed) implements RuleEngine.ActionResult {
        public Result {
            Objects.requireNonNull(progressBefore); Objects.requireNonNull(progressAfter); Objects.requireNonNull(charges);
            validate(progressBefore, charges.before());
            boolean ready = progressBefore.value() == 1;
            var expectedProgress = ready ? reset(progressBefore) : progressBefore;
            var expectedCharges = Resources.grant(charges.before(), ready ? missing(charges.before()) : 0,
                    ready ? missing(charges.before()) : 0);
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
        validate(progress, charges);
        boolean ready = progress.value() == 1;
        double amount = ready ? missing(charges) : 0;
        return new Result(progress, ready ? reset(progress) : progress, Resources.grant(charges, amount, amount), ready);
    }
}
