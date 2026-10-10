package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.stat.Numbers;
import java.util.List;
import java.util.Objects;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Resources {
    public static final long MICROS_PER_SECOND = 1_000_000;
    private Resources() {}
    // State remains double. Arithmetic uses its canonical decimal value so 0.7 + 0.1 reaches
    // the declared 0.8 boundary, without a tolerance that could erase a genuinely small change.
    private static BigDecimal decimal(double value) { return BigDecimal.valueOf(value); }
    private static double add(double left, double right) { return Numbers.finite(decimal(left).add(decimal(right)).doubleValue(), "resource sum"); }
    private static double subtract(double left, double right) { return Numbers.finite(decimal(left).subtract(decimal(right)).doubleValue(), "resource difference"); }
    private static double multiply(double left, double right) { return Numbers.finite(decimal(left).multiply(decimal(right)).doubleValue(), "resource product"); }

    public static ResourceResult grant(ResourceState state, double requested, double scaled) {
        Numbers.nonnegative(requested, "requested grant");
        Numbers.nonnegative(scaled, "scaled grant");
        double credited = Math.min(subtract(state.capacity(), state.value()), scaled);
        var after = new ResourceState(state.key(), Math.min(state.capacity(), add(state.value(), credited)), state.capacity(), state.timeMicros());
        return new ResourceResult(state, after, requested, scaled, credited, subtract(scaled, credited));
    }

    /** No attribute / recipient scaling applies here. The argument is a number of charge units. */
    public static ResourceResult grantFullCharges(ResourceState state, int count) {
        if (count < 0) throw new IllegalArgumentException("Negative full charge grant");
        return grant(state, count, count);
    }

    /** Explicit account resize: keep charge units, discarding only the amount above the new ceiling. */
    public record ResizeResult(ResourceState before, ResourceState after, double discarded) implements com.imdomestic.chorus.rule.RuleEngine.ActionResult {
        public ResizeResult {
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (!before.key().equals(after.key()) || before.timeMicros() != after.timeMicros() || after.capacity() <= 0
                    || after.value() != Math.min(before.value(), after.capacity()) || discarded != subtract(before.value(), after.value()))
                throw new IllegalArgumentException("Invalid resource resize receipt");
        }
        public boolean changed() { return before.capacity() != after.capacity(); }
    }
    public static ResizeResult resize(ResourceState state, double capacity) {
        Numbers.nonnegative(capacity, "resource capacity");
        if (capacity == 0) throw new IllegalArgumentException("Resource capacity must be positive");
        var after = capacity == state.capacity() ? state : new ResourceState(state.key(), Math.min(state.value(), capacity), capacity, state.timeMicros());
        return new ResizeResult(state, after, subtract(state.value(), after.value()));
    }

    public record CostReceipt(String operation, ResourceState.Key account, double paid, double refundClaimed) {
        public CostReceipt {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(account);
            Numbers.nonnegative(paid, "paid cost");
            Numbers.nonnegative(refundClaimed, "claimed refund");
            if (refundClaimed > paid) throw new IllegalArgumentException("Refund exceeds paid cost");
        }
    }
    /** Immutable cost snapshots; the executor retains the latest claim for a payment across actions. */
    public interface CostResult extends com.imdomestic.chorus.rule.RuleEngine.RetainedResult {
        CostReceipt receipt();
        @Override default String retentionKey() { return receipt().operation(); }
    }
    public record SpendResult(ResourceState after, boolean succeeded, CostReceipt receipt) implements CostResult {}
    public record RefundResult(ResourceResult grant, CostReceipt receipt) implements CostResult {}

    public static CostReceipt latestClaim(CostReceipt referenced, Iterable<? extends com.imdomestic.chorus.rule.RuleEngine.ActionResult> results) {
        var latest = referenced;
        for (var result : results) if (result instanceof CostResult cost && cost.receipt().operation().equals(referenced.operation())) {
            var candidate = cost.receipt();
            if (!candidate.account().equals(referenced.account()) || candidate.paid() != referenced.paid()) {
                throw new IllegalArgumentException("Conflicting payment identity: " + referenced.operation());
            }
            if (candidate.refundClaimed() > latest.refundClaimed()) latest = candidate;
        }
        return latest;
    }

    public static double remaining(CostReceipt cost) { return subtract(cost.paid(), cost.refundClaimed()); }

    public static SpendResult spend(ResourceState state, String operation, double amount) {
        Numbers.nonnegative(amount, "cost");
        boolean succeeded = state.value() >= amount;
        double paid = succeeded ? amount : 0;
        return new SpendResult(new ResourceState(state.key(), subtract(state.value(), paid), state.capacity(), state.timeMicros()),
                succeeded, new CostReceipt(operation, state.key(), paid, 0));
    }

    /** Caller stores the returned receipt; operation replay is handled by the rule executor. */
    public static RefundResult refund(ResourceState state, CostReceipt cost, double fraction) {
        Numbers.fraction(fraction, "refund fraction");
        if (!state.key().equals(cost.account())) throw new IllegalArgumentException("Refund belongs to a different account");
        double requested = multiply(cost.paid(), fraction);
        double allowed = Math.min(requested, subtract(cost.paid(), cost.refundClaimed()));
        return new RefundResult(grant(state, requested, allowed), new CostReceipt(cost.operation(), cost.account(),
                cost.paid(), Math.min(cost.paid(), add(cost.refundClaimed(), allowed))));
    }

    /** Changes are the chronological breakpoints supplied by expiration / attribute / cost processing. */
    public record RateChange(long timeMicros, double perSecond) {
        public RateChange {
            if (timeMicros < 0) throw new IllegalArgumentException("Negative rate-change time");
            Numbers.finite(perSecond, "rate");
        }
    }

    public static ResourceState integrate(ResourceState state, long untilMicros, double initialRate, List<RateChange> changes) {
        Numbers.finite(initialRate, "initial rate");
        if (untilMicros < state.timeMicros()) throw new IllegalArgumentException("Cannot integrate backwards");
        long time = state.timeMicros();
        double value = state.value();
        double rate = initialRate;
        for (var change : changes) {
            if (change.timeMicros() < time || change.timeMicros() > untilMicros) {
                throw new IllegalArgumentException("Rate changes must be ordered and inside the integration interval");
            }
            value = integrateSegment(value, state.capacity(), change.timeMicros() - time, rate);
            time = change.timeMicros();
            rate = change.perSecond();
        }
        value = integrateSegment(value, state.capacity(), untilMicros - time, rate);
        return new ResourceState(state.key(), value, state.capacity(), untilMicros);
    }

    private static double integrateSegment(double value, double capacity, long micros, double rate) {
        double change = Numbers.finite(decimal(rate).multiply(BigDecimal.valueOf(micros, 6)).doubleValue(), "integrated change");
        return Math.clamp(add(value, change), 0, capacity);
    }
    /** Ceil to a microsecond, using decimal arithmetic shared with integration. NEVER means unreachable. */
    public static long microsToThreshold(ResourceState account, double target, double rate) {
        Numbers.finite(rate, "resource rate"); Numbers.nonnegative(target, "resource threshold");
        if (target > account.capacity()) throw new IllegalArgumentException("Resource threshold exceeds capacity");
        var difference = decimal(target).subtract(decimal(account.value()));
        if (rate == 0 || difference.signum() == 0 || difference.signum() != Math.signum(rate)) return Long.MAX_VALUE;
        var micros = difference.movePointRight(6).divide(decimal(rate), 0, RoundingMode.CEILING);
        return micros.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : micros.longValueExact();
    }
}
