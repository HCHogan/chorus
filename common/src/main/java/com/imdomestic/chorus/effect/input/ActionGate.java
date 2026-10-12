package com.imdomestic.chorus.effect.input;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.Condition;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Query-only action restrictions. All matching denials apply; none implicitly undo an accepted action. */
public final class ActionGate {
    private ActionGate() {}
    public enum Kind { ABILITY_USE, WEAPON_FIRE, WEAPON_RELOAD, WEAPON_AIM, RANGED_ATTACK, MELEE_ATTACK, MOVEMENT_INPUT, JUMP, HORIZONTAL_MOTION, VERTICAL_MOTION, PROJECTILE_TRACKING }
    public enum Phase { START, CONTINUE, COMPLETE }
    public enum Subject { ACTOR, VICTIM;
        public String holder(EffectEvent event) { return this == ACTOR ? event.actor() : event.victim(); }
    }
    public record Declaration(String id,Kind action,Condition condition,Subject subject) {
        public Declaration(String id,Kind action,Condition condition) { this(id,action,condition,Subject.ACTOR); }
        public Declaration { EffectTimers.localName(id);Objects.requireNonNull(action);Objects.requireNonNull(condition);Objects.requireNonNull(subject); }
    }
    public record Denial(String declaration,String bundle,String instance,BuffInstance.Origin origin,Subject subject,String holder) {
        public Denial { Objects.requireNonNull(declaration);Objects.requireNonNull(bundle);Objects.requireNonNull(instance);Objects.requireNonNull(origin);Objects.requireNonNull(subject);if(holder==null||holder.isBlank())throw new IllegalArgumentException("Denial needs its actual holder"); }
    }
    public record Decision(Kind action,Phase phase,EffectEvent query,List<Denial> denials) implements RuleEngine.ActionResult {
        public Decision { Objects.requireNonNull(action);Objects.requireNonNull(phase);Objects.requireNonNull(query);denials=List.copyOf(denials);if(denials.stream().anyMatch(d->!d.holder().equals(d.subject().holder(query))))throw new IllegalArgumentException("Denial subject differs from query"); }
        public boolean allowed(){return denials.isEmpty();}
    }
    /** Cancellation still exposes the usual event fields while retaining the exact restriction evidence. */
    public record Cancelled(EffectEvent event,Decision decision) implements EffectEvent.Carrier {
        public Cancelled { Objects.requireNonNull(event);Objects.requireNonNull(decision);if(decision.allowed())throw new IllegalArgumentException("Cancellation needs a denied action"); }
    }
    public static void receipt(Kind expected,boolean restricted,Optional<Decision> decision){
        receipt(expected,java.util.Set.of(Phase.START),restricted,decision);
    }
    public static void receipt(Kind expected,java.util.Set<Phase> phases,boolean restricted,Optional<Decision> decision){
        Objects.requireNonNull(decision);
        if(phases.isEmpty()||restricted!=decision.isPresent()||decision.filter(d->d.allowed()||d.action()!=expected||!phases.contains(d.phase())).isPresent())throw new IllegalArgumentException("Restriction receipt lacks matching denial evidence");
    }
}
