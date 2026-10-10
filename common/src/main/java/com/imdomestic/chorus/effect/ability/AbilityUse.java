package com.imdomestic.chorus.effect.ability;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.resource.Resources;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

public final class AbilityUse {
    private AbilityUse() {}
    public static final String EVENT = "chorus:internal/ability_use";
    public static final String STARTED = "chorus:ability_started";
    public enum Outcome { ACCEPTED, EMPTY_SLOT, CONDITION, INSUFFICIENT_ENERGY, CONFLICT, RESTRICTED }
    /** Input facts come from a trusted host, never an arbitrary client event or ability definition. */
    public record Request(String holder, String slot, String cast, EffectEvent input) implements RuleEngine.Payload {
        public Request {
            if (holder == null || holder.isBlank() || cast == null || cast.isBlank()) throw new IllegalArgumentException("Missing ability invocation identity");
            AbilityDefinition.id(slot); Objects.requireNonNull(input);
            if (!input.actor().equals(holder)) throw new IllegalArgumentException("Ability input belongs to another actor");
        }
        public RuleEngine.Signal signal() { return new RuleEngine.Signal(EVENT, this); }
    }
    public record Receipt(String cast, String slot, String base, String resolved, Outcome outcome, Optional<Resources.SpendResult> cost,
            Optional<com.imdomestic.chorus.effect.input.ActionGate.Decision> restriction) implements RuleEngine.ActionResult {
        public Receipt { Objects.requireNonNull(cost);com.imdomestic.chorus.effect.input.ActionGate.receipt(com.imdomestic.chorus.effect.input.ActionGate.Kind.ABILITY_USE,outcome==Outcome.RESTRICTED,restriction);if(outcome==Outcome.RESTRICTED&&cost.isPresent())throw new IllegalArgumentException("Restricted ability cannot have a cost receipt"); }
        public Receipt(String cast,String slot,String base,String resolved,Outcome outcome,Optional<Resources.SpendResult> cost){this(cast,slot,base,resolved,outcome,cost,Optional.empty());}
    }
    /** Accepted and paid; immediate start reactions run before the queued on_use body. */
    public record Started(EffectEvent event, AbilityDefinition definition, Receipt receipt) implements EffectEvent.Carrier {}
    public record Used(EffectEvent event, AbilityDefinition definition, Receipt receipt) implements EffectEvent.Carrier {}
    public record Scope(EffectEvent event) implements RuleEngine.Payload {}
    /** The already-resolved pure write is committed before resource and ability facts can run. */
    public record Commit(EffectState before, EffectState after) implements RuleEngine.Payload {
        public EffectState apply(EffectState current) {
            if (!before.equals(current)) throw new IllegalStateException("Ability acceptance state changed before commit");
            return after;
        }
    }
}
