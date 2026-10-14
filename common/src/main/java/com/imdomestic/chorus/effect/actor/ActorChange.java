package com.imdomestic.chorus.effect.actor;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Trusted host enrollment or replacement; does not select abilities or initialize equipment. */
public record ActorChange(String holder, Optional<ActorBinding> before, Optional<ActorBinding> after) implements RuleEngine.Payload {
    public static final String EVENT = "chorus:internal/actor_change";
    public ActorChange { ActorSources.instance(holder); Objects.requireNonNull(before); Objects.requireNonNull(after); }
    public RuleEngine.Signal signal() { return new RuleEngine.Signal(EVENT, this); }
    public RuleEngine.Local<EffectState> apply(EffectState state, CompiledEffects program) {
        if (!before.equals(Optional.ofNullable(state.actors().get(holder)))) throw new IllegalStateException("Stale actor binding");
        ActorSources.validate(state);
        var previous = ActorSources.sources(holder, before); var next = ActorSources.sources(holder, after);
        previous.values().forEach(program::validateSource); next.values().forEach(program::validateSource);
        var batch = SourceBatch.between(previous, next); batch.validateCurrent(state);
        return batch.apply(before.equals(after) ? state : state.withActor(holder, after));
    }
}
