package com.imdomestic.chorus.effect.weapon;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Server-owned aim input and its accepted transition; no client-provided duration or weapon identity. */
public final class WeaponAim {
    private WeaponAim() {}
    public static final String REQUEST="chorus:internal/aim_request", DUE="chorus:internal/aim_due";
    public enum Phase { ENTERING, AIMED, EXITING }
    public enum Outcome { ACCEPTED, UNCHANGED, EMPTY_HANDS, NOT_CONFIGURED, BUSY, RESTRICTED }
    public record Request(String holder,String token,boolean pressed) implements RuleEngine.Payload {
        public Request { text(holder);text(token); }
        public RuleEngine.Signal signal(){return new RuleEngine.Signal(REQUEST,this);}
    }
    public record Plan(String holder,String token,String slot,Loadout.Gear gear,BuffInstance.Origin origin,Phase phase,
            long startedAt,long dueAt,Measure input,Measure duration,Optional<CalculationPipeline.Result> calculation, long clockOffset) implements RuleEngine.Payload {
        public Plan {
            WeaponPhaseTime.local(startedAt,clockOffset); WeaponPhaseTime.local(dueAt,clockOffset);
            text(holder);text(token);text(slot);Objects.requireNonNull(gear);Objects.requireNonNull(origin);Objects.requireNonNull(phase);
            Objects.requireNonNull(input);Objects.requireNonNull(duration);Objects.requireNonNull(calculation);
            if(!origin.owner().equals(holder)||!origin.weapon().equals(gear.instance())||startedAt<0||dueAt==Long.MAX_VALUE
                    ||dueAt!=Math.addExact(startedAt,WeaponHandling.micros(duration)))throw new IllegalArgumentException("Invalid aim phase identity or clock");
            if(calculation.isPresent()? !calculation.orElseThrow().input().equals(input)||!calculation.orElseThrow().output().equals(duration):!input.equals(duration))
                throw new IllegalArgumentException("Aim timing differs from its accepted calculation");
        }
        public boolean held(){return phase!=Phase.EXITING;}
        public boolean matches(Loadout loadout){return loadout.drawn().filter(slot::equals).isPresent()&&gear.equals(loadout.slots().get(slot));}
        public String timerId(){return "aim/"+holder.length()+":"+holder+"/"+token;}
        public EffectState.Timer timer(){
            if(phase==Phase.AIMED||dueAt<=startedAt)throw new IllegalStateException("No pending timed aim transition");
            return new EffectState.Timer(timerId(),dueTime(),0,1,new RuleEngine.Signal(DUE,this),Optional.empty());
        }
        public Plan aimed(){
            if(phase!=Phase.ENTERING)throw new IllegalStateException("Only an entering aim can complete");
            return new Plan(holder,token,slot,gear,origin,Phase.AIMED,startedAt,dueAt,input,duration,calculation,clockOffset);
        }
        public Plan(String holder,String token,String slot,Loadout.Gear gear,BuffInstance.Origin origin,Phase phase,
            long startedAt,long dueAt,Measure input,Measure duration,Optional<CalculationPipeline.Result> calculation) { this(holder, token, slot, gear, origin, phase, startedAt, dueAt, input, duration, calculation,0); }
        public long startTime() { return WeaponPhaseTime.local(startedAt,clockOffset); }
        public long dueTime() { return WeaponPhaseTime.local(dueAt,clockOffset); }
        public Plan relocated(long sourceNow,long targetNow) {
            WeaponPhaseTime.elapsed(sourceNow,startTime());
            return new Plan(holder, token, slot, gear, origin, phase, startedAt, dueAt, input, duration, calculation,WeaponPhaseTime.relocate(clockOffset,sourceNow,targetNow));
        }

    }
    public record Receipt(Outcome outcome,Optional<Plan> plan,Optional<ActionGate.Decision> restriction) implements RuleEngine.ActionResult {
        public Receipt {Objects.requireNonNull(outcome);Objects.requireNonNull(plan);ActionGate.receipt(ActionGate.Kind.WEAPON_AIM,outcome==Outcome.RESTRICTED,restriction);if((outcome==Outcome.ACCEPTED)!=plan.isPresent())throw new IllegalArgumentException("Accepted aim needs its plan");}
    }
    public record Scope(EffectEvent event) implements RuleEngine.Payload {public Scope {Objects.requireNonNull(event);}}
    public record Fact(EffectEvent event,Plan plan) implements EffectEvent.Carrier {public Fact {Objects.requireNonNull(event);Objects.requireNonNull(plan);}}
    public record Verify(Plan plan) implements RuleEngine.WorldCommand {public Verify {Objects.requireNonNull(plan);}}
    public record Verified(Verify query,boolean allowed) implements RuleEngine.ActionResult {public Verified {Objects.requireNonNull(query);}}
    public record Commit(EffectState before,EffectState after) implements RuleEngine.Payload {
        public Commit {Objects.requireNonNull(before);Objects.requireNonNull(after);}
        public EffectState apply(EffectState current){if(!current.equals(before))throw new IllegalStateException("Aim preflight no longer matches state");return after;}
    }
    private static void text(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("Missing aim identity");}
}
