package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** One instantaneous energy packet, with one captured yield per cycle. Never iterates once per charge. */
public final class CyclicRecharge {
    private CyclicRecharge() {}
    public record Result(ResourceState progressBefore, ResourceState progressAfter, ResourceResult uses,
            double energy, double credited, double overflow, double cycles, double yield) implements RuleEngine.ActionResult {}
    private static BigDecimal decimal(double value) { return BigDecimal.valueOf(value); }
    private static double number(BigDecimal value) { return Numbers.nonnegative(value.doubleValue(), "cyclic recharge result"); }
    public static boolean completes(ResourceState progress, double energy) {
        Numbers.nonnegative(energy, "recharge energy");
        return decimal(progress.value()).add(decimal(energy)).compareTo(BigDecimal.ONE) >= 0;
    }
    public static Result grant(ResourceState progress, ResourceState uses, double energy, double cycleYield) {
        Objects.requireNonNull(progress); Objects.requireNonNull(uses);
        if (!progress.key().holder().equals(uses.key().holder()) || progress.key().equals(uses.key())
                || progress.timeMicros() != uses.timeMicros() || progress.capacity() != 1 || uses.capacity() <= 0)
            throw new IllegalArgumentException("Cyclic recharge requires distinct simultaneous accounts and a one-unit meter");
        Numbers.nonnegative(energy, "recharge energy"); Numbers.nonnegative(cycleYield, "recharge cycle yield");
        var incoming = decimal(energy); var before = decimal(progress.value());
        var missing = decimal(uses.capacity()).subtract(decimal(uses.value()));
        if (missing.signum() == 0) return new Result(progress, progress, Resources.grant(uses, 0, 0), energy, 0, energy, 0, cycleYield);
        var total = before.add(incoming); var cycles = total.setScale(0, RoundingMode.FLOOR);
        var yield = decimal(cycleYield);
        if (yield.signum() > 0) cycles = cycles.min(missing.divide(yield, 0, RoundingMode.CEILING));
        double produced = number(cycles.multiply(yield));
        var granted = Resources.grant(uses, produced, produced);
        boolean full = granted.after().value() == granted.after().capacity();
        var accepted = full ? cycles.subtract(before).max(BigDecimal.ZERO).min(incoming) : incoming;
        var remainder = full ? BigDecimal.ZERO : total.subtract(cycles);
        return new Result(progress, new ResourceState(progress.key(), number(remainder), 1, progress.timeMicros()), granted,
                energy, number(accepted), number(incoming.subtract(accepted)), number(cycles), cycleYield);
    }
}
