package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;
import java.util.Set;

/** One explicit health-healing request. Shield repair is a separate operation. */
public record HealingCommand(String target, BuffInstance.Origin source, double amount, Set<String> tags)
        implements RuleEngine.WorldCommand {
    public HealingCommand {
        Objects.requireNonNull(target); Objects.requireNonNull(source); tags = Set.copyOf(tags);
        if (target.isBlank()) throw new IllegalArgumentException("Missing healing target");
        Numbers.nonnegative(amount, "requested healing");
    }
}
