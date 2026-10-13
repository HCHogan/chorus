package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffDefinition;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;
import java.util.Optional;

/** Eligibility is checked at the world boundary; applied means the Chorus buff was then committed. */
public record StatusResult(Optional<String> cause, boolean applied, double before, double after,
        Optional<EffectState.Lifetime> instance, Decision decision) implements RuleEngine.ActionResult {
    public enum Decision { ALLOWED, DENIED, DEAD, MISSING }
    public record Check(String target, BuffDefinition definition, BuffInstance.Origin source, double stacks, int tier, long duration, boolean allowDead)
            implements RuleEngine.WorldCommand {
        public Check {
            Objects.requireNonNull(target); Objects.requireNonNull(definition); Objects.requireNonNull(source);
            com.imdomestic.chorus.effect.buff.Buffs.validateQuantity(definition, stacks);
            if (tier < 1 || duration < 1) throw new IllegalArgumentException("Invalid status request");
        }
        public Check(String target, BuffDefinition definition, BuffInstance.Origin source, double stacks, int tier, long duration) {
            this(target, definition, source, stacks, tier, duration, false);
        }
    }
    public record Checked(Check request, Decision decision) implements RuleEngine.ActionResult {
        public Checked { Objects.requireNonNull(request); Objects.requireNonNull(decision); }
    }
    public StatusResult {
        Objects.requireNonNull(cause); Objects.requireNonNull(instance); Objects.requireNonNull(decision);
        if (!Double.isFinite(before) || !Double.isFinite(after) || before < 0 || after < 0 || (applied && (decision != Decision.ALLOWED || after == 0 || instance.isEmpty()))
                || (!applied && (before != after || instance.isPresent()))) throw new IllegalArgumentException("Invalid status receipt");
    }
}
