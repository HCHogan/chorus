package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffDefinition;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;
import java.util.Optional;

/** Eligibility is checked at the world boundary; applied means the Chorus buff was then committed. */
public record StatusResult(Optional<String> cause, boolean applied, int before, int after,
        Optional<EffectState.Lifetime> instance, Decision decision) implements RuleEngine.ActionResult {
    public enum Decision { ALLOWED, DENIED, DEAD, MISSING }
    public record Check(String target, BuffDefinition definition, BuffInstance.Origin source, int stacks, int tier, long duration, boolean allowDead)
            implements RuleEngine.WorldCommand {
        public Check(String target, BuffDefinition definition, BuffInstance.Origin source, int stacks, int tier, long duration) {
            this(target, definition, source, stacks, tier, duration, false);
        }
    }
    public record Checked(Check request, Decision decision) implements RuleEngine.ActionResult {
        public Checked { Objects.requireNonNull(request); Objects.requireNonNull(decision); }
    }
    public StatusResult {
        Objects.requireNonNull(cause); Objects.requireNonNull(instance); Objects.requireNonNull(decision);
        if (before < 0 || after < 0 || (applied && (decision != Decision.ALLOWED || after == 0 || instance.isEmpty()))
                || (!applied && (before != after || instance.isPresent()))) throw new IllegalArgumentException("Invalid status receipt");
    }
}
