package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import com.imdomestic.chorus.stat.Unit;
import java.util.Objects;
import java.util.Set;

/** One explicit health-healing request. Shield repair is a separate operation. */
public record HealingCommand(String target, BuffInstance.Origin source, double amount, Set<String> tags, Basis basis)
        implements RuleEngine.WorldCommand {
    public enum Basis {
        ABSOLUTE(Unit.DAMAGE, Unit.DAMAGE_PER_SECOND), MAX_HEALTH(Unit.HEALTH_FRACTION, Unit.HEALTH_FRACTION_PER_SECOND);
        private final Unit amountUnit, rateUnit;
        Basis(Unit amountUnit, Unit rateUnit) { this.amountUnit = amountUnit; this.rateUnit = rateUnit; }
        public Unit amountUnit() { return amountUnit; }
        public Unit rateUnit() { return rateUnit; }
        public double resolve(double amount, double maximumHealth) {
            Numbers.nonnegative(amount, "healing quantity"); Numbers.nonnegative(maximumHealth, "maximum health");
            double resolved = this == ABSOLUTE ? amount : amount * maximumHealth;
            Numbers.nonnegative(resolved, "resolved healing"); return resolved;
        }
    }
    public HealingCommand(String target, BuffInstance.Origin source, double amount, Set<String> tags) {
        this(target, source, amount, tags, Basis.ABSOLUTE);
    }
    public HealingCommand {
        Objects.requireNonNull(target); Objects.requireNonNull(source); Objects.requireNonNull(basis); tags = Set.copyOf(tags);
        if (target.isBlank()) throw new IllegalArgumentException("Missing healing target");
        Numbers.nonnegative(amount, "requested healing");
    }
}
