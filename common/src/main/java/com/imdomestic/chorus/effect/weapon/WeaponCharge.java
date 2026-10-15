package com.imdomestic.chorus.effect.weapon;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.target.EntityAnchor;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Accepted gameplay state is distinct from a connection's held-button intent. No ammunition is reserved by pressing. */
public final class WeaponCharge {
    private WeaponCharge() {}
    public static final String REQUEST="chorus:internal/charge_request", DUE="chorus:internal/charge_due";
    public enum Edge { PRESS, RELEASE, CANCEL }
    public enum Trigger { RELEASE, FULL }
    public enum Phase { CHARGING, READY, RECOVERING }
    public enum Outcome { ACCEPTED, ALREADY_HELD, EMPTY_HANDS, NOT_CONFIGURED, BUSY, COOLDOWN, CONDITION, RESTRICTED, NO_PRESS, STALE, CANCELLED, TOO_EARLY, FIRED, FIRE_REJECTED }
    public record Request(String holder,String token,Edge edge,Optional<EntityAnchor.Reference> holderReference) implements RuleEngine.Payload {
        public Request { text(holder);text(token);Objects.requireNonNull(edge);Objects.requireNonNull(holderReference);holderReference.ifPresent(ref->{if(!ref.target().equals(holder))throw new IllegalArgumentException("Charge holder reference differs");}); }
        public Request(String holder,String token,Edge edge){this(holder,token,edge,Optional.empty());}
        public RuleEngine.Signal signal(){return new RuleEngine.Signal(REQUEST,this);}
    }
    public record Timing(Measure input,Measure duration,Optional<CalculationPipeline.Result> calculation) {
        public Timing {
            Objects.requireNonNull(input);Objects.requireNonNull(duration);Objects.requireNonNull(calculation);WeaponHandling.micros(duration);
            if(calculation.isPresent()? !calculation.orElseThrow().input().equals(input)||!calculation.orElseThrow().output().equals(duration):!input.equals(duration))
                throw new IllegalArgumentException("Charge timing differs from its accepted calculation");
        }
        public long micros(){return WeaponHandling.micros(duration);}
    }
    public record Plan(String holder,String token,String slot,Loadout.Gear gear,BuffInstance.Origin origin,Phase phase,
            long startedAt,long fullAt,Timing minimum,Timing full,Optional<EntityAnchor.Reference> holderReference,Trigger trigger,
            boolean repeat,long cycle,Optional<WeaponFire.Shot> recovery, long clockOffset) implements RuleEngine.Payload {
        public Plan {
            WeaponPhaseTime.local(startedAt,clockOffset); WeaponPhaseTime.local(fullAt,clockOffset);
            text(holder);text(token);text(slot);Objects.requireNonNull(gear);Objects.requireNonNull(origin);Objects.requireNonNull(phase);Objects.requireNonNull(minimum);Objects.requireNonNull(full);
            Objects.requireNonNull(trigger);Objects.requireNonNull(holderReference);holderReference.ifPresent(ref->{if(!ref.target().equals(holder))throw new IllegalArgumentException("Charge plan holder reference differs");});
            Objects.requireNonNull(recovery);
            if(cycle<0||!repeat&&cycle!=0||repeat&&trigger!=Trigger.FULL||(phase==Phase.RECOVERING)!=recovery.isPresent()
                    ||recovery.isPresent()&&!repeat)throw new IllegalArgumentException("Invalid repeated charge lifecycle");
            if(recovery.isPresent()){
                var shot=recovery.orElseThrow();
                if(!shot.holder().equals(holder)||!shot.gear().equals(gear)||!shot.token().equals(fireToken(token,repeat,cycle))||shot.firedTime()!=WeaponPhaseTime.local(fullAt,clockOffset))
                    throw new IllegalArgumentException("Charge recovery differs from its accepted shot");
            }
            if(!origin.owner().equals(holder)||!origin.weapon().equals(gear.instance())||!origin.source().equals("charge/"+token)
                    ||startedAt<0||fullAt==Long.MAX_VALUE||full.micros()<=0||minimum.micros()>full.micros()
                    ||fullAt!=Math.addExact(startedAt,full.micros()))throw new IllegalArgumentException("Invalid charge identity or timing");
        }
        public Plan(String holder,String token,String slot,Loadout.Gear gear,BuffInstance.Origin origin,Phase phase,
                long startedAt,long fullAt,Timing minimum,Timing full,Optional<EntityAnchor.Reference> holderReference,Trigger trigger) {
            this(holder,token,slot,gear,origin,phase,startedAt,fullAt,minimum,full,holderReference,trigger,false,0,Optional.empty());
        }
        public Plan(String holder,String token,String slot,Loadout.Gear gear,BuffInstance.Origin origin,Phase phase,
                long startedAt,long fullAt,Timing minimum,Timing full,Optional<EntityAnchor.Reference> holderReference) {
            this(holder,token,slot,gear,origin,phase,startedAt,fullAt,minimum,full,holderReference,Trigger.RELEASE);
        }
        public boolean matches(Loadout loadout){return loadout.drawn().filter(slot::equals).isPresent()&&gear.equals(loadout.slots().get(slot));}
        public String timerId(){return "charge/"+holder.length()+":"+holder+"/"+token;}
        public long dueAt(){return recovery.map(WeaponFire.Shot::readyTime).orElse(fullTime());}
        public String fireToken(){return fireToken(token,repeat,cycle);}
        private static String fireToken(String token,boolean repeat,long cycle){return repeat?"charge-cycle/"+token.length()+":"+token+"/"+cycle:token;}
        public EffectState.Timer timer(){
            if(phase==Phase.READY)throw new IllegalStateException("Ready charge has no pending timer");
            return new EffectState.Timer(timerId(),dueAt(),0,1,new RuleEngine.Signal(DUE,this),Optional.empty());
        }
        public Plan ready(){return new Plan(holder,token,slot,gear,origin,Phase.READY,startedAt,fullAt,minimum,full,holderReference,trigger,repeat,cycle,Optional.empty(),clockOffset);}
        public Plan recovering(WeaponFire.Shot shot){return new Plan(holder,token,slot,gear,origin,Phase.RECOVERING,startedAt,fullAt,minimum,full,holderReference,trigger,repeat,cycle,Optional.of(shot),clockOffset);}
        public Map<String,Measure> numbers(long now){
            if(now<startTime())throw new IllegalArgumentException("Charge observation precedes acceptance");
            long elapsed=WeaponPhaseTime.elapsed(now,startTime());
            return Map.of("charge_elapsed",new Measure(elapsed/1_000_000.0,Unit.SECOND),
                    "charge_minimum",new Measure(minimum.micros()/1_000_000.0,Unit.SECOND),
                    "charge_duration",new Measure(full.micros()/1_000_000.0,Unit.SECOND),
                    "charge_fraction",new Measure(Math.min(1,(double)elapsed/full.micros()),Unit.MULTIPLIER),
                    "charge_cycle",new Measure(cycle,Unit.COUNT),
                    "charge_overdraw",new Measure(Math.max(0,Math.subtractExact(now,fullTime()))/1_000_000.0,Unit.SECOND));
        }
        public EffectEvent event(long now,boolean held){
            return new EffectEvent(holder,gear.instance(),origin,origin.tags(),numbers(now),
                    Map.of("charged",true,"charge_held",held,"fully_charged",now>=fullTime(),"charge_repeating",repeat,"charge_recovering",phase==Phase.RECOVERING),
                    Map.of("weapon",gear.instance(),"item",gear.definition(),"charge",token,"charge_trigger",trigger.name().toLowerCase(Locale.ROOT)));
        }
        public Plan(String holder,String token,String slot,Loadout.Gear gear,BuffInstance.Origin origin,Phase phase,
            long startedAt,long fullAt,Timing minimum,Timing full,Optional<EntityAnchor.Reference> holderReference,Trigger trigger,
            boolean repeat,long cycle,Optional<WeaponFire.Shot> recovery) { this(holder, token, slot, gear, origin, phase, startedAt, fullAt, minimum, full, holderReference, trigger, repeat, cycle, recovery,0); }
        public long startTime() { return WeaponPhaseTime.local(startedAt,clockOffset); }
        public long fullTime() { return WeaponPhaseTime.local(fullAt,clockOffset); }
        public Plan relocated(long sourceNow,long targetNow) {
            WeaponPhaseTime.elapsed(sourceNow,startTime());
            return new Plan(holder, token, slot, gear, origin, phase, startedAt, fullAt, minimum, full, holderReference, trigger, repeat, cycle, recovery.map(value->value.relocated(sourceNow,targetNow)),WeaponPhaseTime.relocate(clockOffset,sourceNow,targetNow));
        }

    }
    public record Receipt(Outcome outcome,Optional<Plan> plan,Optional<WeaponFire.Receipt> fire,Optional<ActionGate.Decision> restriction) implements RuleEngine.ActionResult {
        public Receipt {
            Objects.requireNonNull(outcome);Objects.requireNonNull(plan);Objects.requireNonNull(fire);
            ActionGate.receipt(ActionGate.Kind.WEAPON_FIRE,outcome==Outcome.RESTRICTED,restriction);
            boolean release=outcome==Outcome.FIRED||outcome==Outcome.FIRE_REJECTED;
            boolean carriesPlan=release||outcome==Outcome.ACCEPTED||outcome==Outcome.CANCELLED||outcome==Outcome.TOO_EARLY;
            if(release!=fire.isPresent()||carriesPlan!=plan.isPresent()
                    ||fire.isPresent()&&(outcome==Outcome.FIRED)!=(fire.orElseThrow().outcome()==WeaponFire.Outcome.ACCEPTED))
                throw new IllegalArgumentException("Invalid charge receipt");
        }
    }
    public record Fact(EffectEvent event,Plan plan) implements EffectEvent.Carrier {public Fact {Objects.requireNonNull(event);Objects.requireNonNull(plan);}}
    public record Verify(Plan plan) implements RuleEngine.WorldCommand {public Verify {Objects.requireNonNull(plan);}}
    public record Verified(Verify query,boolean allowed) implements RuleEngine.ActionResult {public Verified {Objects.requireNonNull(query);}}
    public record Commit(EffectState before,EffectState after) implements RuleEngine.Payload {
        public Commit {Objects.requireNonNull(before);Objects.requireNonNull(after);}
        public EffectState apply(EffectState current){if(!current.equals(before))throw new IllegalStateException("Charge preflight no longer matches state");return after;}
    }
    private static void text(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("Missing charge identity");}
}
