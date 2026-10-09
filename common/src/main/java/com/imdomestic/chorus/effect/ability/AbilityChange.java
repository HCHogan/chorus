package com.imdomestic.chorus.effect.ability;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Trusted host selection transaction; subclasses and unlocks must be checked before this boundary. */
public record AbilityChange(String holder, AbilityLoadout before, AbilityLoadout after) implements RuleEngine.Payload {
    public static final String EVENT = "chorus:internal/ability_change";
    public AbilityChange { if (holder == null || holder.isBlank()) throw new IllegalArgumentException("Missing ability holder"); Objects.requireNonNull(before); Objects.requireNonNull(after); }
    public RuleEngine.Signal signal() { return new RuleEngine.Signal(EVENT, this); }
    public RuleEngine.Local<EffectState> apply(EffectState state, CompiledEffects program) { return program.changeAbilities(state, this); }
}
