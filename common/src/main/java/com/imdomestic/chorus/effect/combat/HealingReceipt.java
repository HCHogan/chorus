package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;
import java.util.OptionalDouble;

/** Actual health writes. Offered includes loader modification; overheal is excess over available health capacity. */
public record HealingReceipt(String healId, HealingCommand command, Outcome outcome, double offered, double effective, double overheal, OptionalDouble maximumHealth)
        implements RuleEngine.ActionResult {
    public enum Outcome { APPLIED, REJECTED, DEAD, MISSING }
    public HealingReceipt {
        Objects.requireNonNull(healId); Objects.requireNonNull(command); Objects.requireNonNull(outcome);
        Objects.requireNonNull(maximumHealth);
        maximumHealth.ifPresent(value -> command.basis().resolve(command.amount(), value));
        if (outcome == Outcome.APPLIED && command.basis() == HealingCommand.Basis.MAX_HEALTH && maximumHealth.isEmpty()) {
            throw new IllegalArgumentException("Applied fractional healing requires a maximum-health observation");
        }
        if (healId.isBlank()) throw new IllegalArgumentException("Missing healing identity");
        Numbers.nonnegative(offered, "offered healing"); Numbers.nonnegative(effective, "effective healing"); Numbers.nonnegative(overheal, "overheal");
        if (overheal > offered) throw new IllegalArgumentException("Overheal exceeds offered healing");
        if (outcome != Outcome.APPLIED && (offered != 0 || effective != 0 || overheal != 0)) {
            throw new IllegalArgumentException("Unapplied healing cannot report offered or committed quantities");
        }
    }
    public HealingReceipt(String healId, HealingCommand command, Outcome outcome, double offered, double effective, double overheal) {
        this(healId, command, outcome, offered, effective, overheal, OptionalDouble.empty());
    }
    public boolean amountResolved() { return command.basis() == HealingCommand.Basis.ABSOLUTE || maximumHealth.isPresent(); }
    public double requested() {
        if (command.basis() == HealingCommand.Basis.ABSOLUTE) return command.amount();
        if (maximumHealth.isEmpty()) throw new IllegalArgumentException("Healing target maximum health was not observed");
        return command.basis().resolve(command.amount(), maximumHealth.getAsDouble());
    }
    public boolean applied() { return outcome == Outcome.APPLIED; }
    public static HealingReceipt unapplied(String id, HealingCommand command, Outcome outcome) {
        return unapplied(id, command, outcome, OptionalDouble.empty());
    }
    public static HealingReceipt unapplied(String id, HealingCommand command, Outcome outcome, OptionalDouble maximumHealth) {
        if (outcome == Outcome.APPLIED) throw new IllegalArgumentException("Expected unapplied outcome");
        return new HealingReceipt(id, command, outcome, 0, 0, 0, maximumHealth);
    }
}
