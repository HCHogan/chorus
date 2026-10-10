package com.imdomestic.chorus.effect.input;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.Condition;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Query-only action restrictions. All matching denials apply; none implicitly undo an accepted action. */
public final class ActionGate {
    private ActionGate() {}
    public enum Kind { ABILITY_USE, WEAPON_FIRE, WEAPON_RELOAD, RANGED_ATTACK, MELEE_ATTACK }
    public enum Phase { START, CONTINUE, COMPLETE }
    public record Declaration(String id,Kind action,Condition condition) {
        public Declaration { EffectTimers.localName(id);Objects.requireNonNull(action);Objects.requireNonNull(condition); }
    }
    public record Denial(String declaration,String bundle,String instance,BuffInstance.Origin origin) {
        public Denial { Objects.requireNonNull(declaration);Objects.requireNonNull(bundle);Objects.requireNonNull(instance);Objects.requireNonNull(origin); }
    }
    public record Decision(Kind action,Phase phase,EffectEvent query,List<Denial> denials) implements RuleEngine.ActionResult {
        public Decision { Objects.requireNonNull(action);Objects.requireNonNull(phase);Objects.requireNonNull(query);denials=List.copyOf(denials); }
        public boolean allowed(){return denials.isEmpty();}
    }
    /** Cancellation still exposes the usual event fields while retaining the exact restriction evidence. */
    public record Cancelled(EffectEvent event,Decision decision) implements EffectEvent.Carrier {
        public Cancelled { Objects.requireNonNull(event);Objects.requireNonNull(decision);if(decision.allowed())throw new IllegalArgumentException("Cancellation needs a denied action"); }
    }
    public static void receipt(Kind expected,boolean restricted,Optional<Decision> decision){
        Objects.requireNonNull(decision);
        if(restricted!=decision.isPresent()||decision.filter(d->d.allowed()||d.action()!=expected||d.phase()!=Phase.START).isPresent())throw new IllegalArgumentException("Restriction receipt lacks matching denial evidence");
    }
}
