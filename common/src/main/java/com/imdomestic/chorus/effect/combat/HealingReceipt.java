package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;

/** Actual health writes. Offered includes loader modification; overheal is excess over available health capacity. */
public record HealingReceipt(String healId, HealingCommand command, Outcome outcome, double offered, double effective, double overheal)
        implements RuleEngine.ActionResult {
    public enum Outcome { APPLIED, REJECTED, DEAD, MISSING }
    public HealingReceipt {
        Objects.requireNonNull(healId); Objects.requireNonNull(command); Objects.requireNonNull(outcome);
        if (healId.isBlank()) throw new IllegalArgumentException("Missing healing identity");
        Numbers.nonnegative(offered, "offered healing"); Numbers.nonnegative(effective, "effective healing"); Numbers.nonnegative(overheal, "overheal");
        if (overheal > offered) throw new IllegalArgumentException("Overheal exceeds offered healing");
        if (outcome != Outcome.APPLIED && (offered != 0 || effective != 0 || overheal != 0)) {
            throw new IllegalArgumentException("Unapplied healing cannot report offered or committed quantities");
        }
    }
    public double requested() { return command.amount(); }
    public boolean applied() { return outcome == Outcome.APPLIED; }
    public static HealingReceipt unapplied(String id, HealingCommand command, Outcome outcome) {
        if (outcome == Outcome.APPLIED) throw new IllegalArgumentException("Expected unapplied outcome");
        return new HealingReceipt(id, command, outcome, 0, 0, 0);
    }
}
