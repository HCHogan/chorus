package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** A press fixes its timing; the matched release consumes that press before any shot body can run. */
final class CompiledCharging {
    private CompiledCharging() {}
    static RuleEngine.Local<EffectState> input(EffectState state,WeaponCharge.Request request,CompiledWeapons weapons,CompiledEffects program){
        var previous=state.charging().get(request.holder());
        if(request.edge()!=WeaponCharge.Edge.PRESS){
            if(previous==null)return reject(state,WeaponCharge.Outcome.NO_PRESS);
            if(!previous.token().equals(request.token())||!previous.holderReference().equals(request.holderReference()))return reject(state,WeaponCharge.Outcome.STALE);
            if(request.edge()==WeaponCharge.Edge.CANCEL)return ended(state,previous,WeaponCharge.Outcome.CANCELLED,"input_cancelled");
            if(!previous.matches(state.equipment().getOrDefault(request.holder(),Loadout.EMPTY)))return ended(state,previous,WeaponCharge.Outcome.CANCELLED,"equipment_changed");
            if(state.buffs().timeMicros()-previous.startedAt()<previous.minimum().micros())return ended(state,previous,WeaponCharge.Outcome.TOO_EARLY,"released_early");
            var fired=weapons.fire(state,new WeaponFire.Request(request.holder(),request.token()),program,Optional.of(previous));
            var receipt=(WeaponFire.Receipt)fired.result();var facts=new ArrayList<RuleEngine.Signal>();
            facts.add(fact("chorus:weapon_charge_ended",previous,"released",state.buffs().timeMicros(),false));facts.addAll(fired.emitted());
            return new RuleEngine.Local<>(clear(fired.state(),previous),new WeaponCharge.Receipt(receipt.outcome()==WeaponFire.Outcome.ACCEPTED?WeaponCharge.Outcome.FIRED:WeaponCharge.Outcome.FIRE_REJECTED,Optional.of(previous),Optional.of(receipt),Optional.empty()),facts);
        }
        if(previous!=null)return reject(state,WeaponCharge.Outcome.ALREADY_HELD);
        var loadout=state.equipment().getOrDefault(request.holder(),Loadout.EMPTY);var gear=WeaponReload.drawn(loadout);
        if(gear.isEmpty())return reject(state,WeaponCharge.Outcome.EMPTY_HANDS);
        var weapon=gear.orElseThrow();var definition=weapons.definition(weapon.definition());
        if(definition==null||definition.charging().isEmpty())return reject(state,WeaponCharge.Outcome.NOT_CONFIGURED);
        if(state.handling().containsKey(request.holder())||state.reloads().containsKey(request.holder()))return reject(state,WeaponCharge.Outcome.BUSY);
        long now=state.buffs().timeMicros();var shot=state.shots().get(weapon.instance());
        if(shot!=null&&now<shot.readyAt())return reject(state,WeaponCharge.Outcome.COOLDOWN);
        var tags=new HashSet<>(weapons.itemTags(weapon.definition()));tags.addAll(definition.fire().orElseThrow().tags());tags.add("chorus:weapon_charge");
        var origin=new BuffInstance.Origin(request.holder(),"charge/"+request.token(),weapon.instance(),"",tags);
        var query=new EffectEvent(request.holder(),weapon.instance(),origin,tags,Map.of("charge_elapsed",new Measure(0,Unit.SECOND),"charge_fraction",new Measure(0,Unit.MULTIPLIER),"charge_overdraw",new Measure(0,Unit.SECOND)),Map.of("charged",true,"charge_held",true,"fully_charged",false),Map.of("weapon",weapon.instance(),"item",weapon.definition(),"charge",request.token()));
        var gate=program.checkAction(state,ActionGate.Kind.WEAPON_FIRE,ActionGate.Phase.START,query);
        if(!gate.allowed())return new RuleEngine.Local<>(state,new WeaponCharge.Receipt(WeaponCharge.Outcome.RESTRICTED,Optional.empty(),Optional.empty(),Optional.of(gate)),List.of());
        var charging=definition.charging().orElseThrow();
        if(!charging.condition().test(evaluation(state,query,program)))return reject(state,WeaponCharge.Outcome.CONDITION);
        var minimum=timing(state,query,charging.minimum(),"minimum",program);var full=timing(state,query,charging.full(),"full",program);
        var plan=new WeaponCharge.Plan(request.holder(),request.token(),loadout.drawn().orElseThrow(),weapon,origin,WeaponCharge.Phase.CHARGING,now,Math.addExact(now,full.micros()),minimum,full,request.holderReference());
        return new RuleEngine.Local<>(state.withCharge(plan).schedule(plan.timer()),new WeaponCharge.Receipt(WeaponCharge.Outcome.ACCEPTED,Optional.of(plan),Optional.empty(),Optional.empty()),List.of(fact("chorus:weapon_charge_started",plan,"input",now,true)));
    }
    private static Evaluation evaluation(EffectState state,EffectEvent query,CompiledEffects program){
        var event=new RuleEngine.Event(0,0,Optional.empty(),state.buffs().timeMicros(),new RuleEngine.Signal("chorus:internal/charge_query",query));
        return program.evaluation(state,new RuleEngine.Context(event,query.source().source(),new WeaponFire.Scope(query),Map.of()),Map.of());
    }
    private static WeaponCharge.Timing timing(EffectState state,EffectEvent query,WeaponDefinition.Timing timing,String name,CompiledEffects program){
        var refs=new HashMap<>(query.references());refs.put("charge_timing",name);query=new EffectEvent(query.actor(),query.victim(),query.source(),query.tags(),query.numbers(),query.flags(),refs);
        var input=timing.value().evaluate(evaluation(state,query,program));
        var calculation=timing.profiles().isEmpty()?Optional.<CalculationPipeline.Result>empty():Optional.of(program.calculatePipeline(state,query.actor(),query,timing.profiles(),input));
        return new WeaponCharge.Timing(input,calculation.map(CalculationPipeline.Result::output).orElse(input),calculation);
    }
    private static RuleEngine.Local<EffectState> reject(EffectState state,WeaponCharge.Outcome outcome){return new RuleEngine.Local<>(state,new WeaponCharge.Receipt(outcome,Optional.empty(),Optional.empty(),Optional.empty()),List.of());}
    private static RuleEngine.Local<EffectState> ended(EffectState state,WeaponCharge.Plan plan,WeaponCharge.Outcome outcome,String reason){
        return new RuleEngine.Local<>(clear(state,plan),new WeaponCharge.Receipt(outcome,Optional.of(plan),Optional.empty(),Optional.empty()),List.of(fact("chorus:weapon_charge_ended",plan,reason,state.buffs().timeMicros(),false)));
    }
    static boolean current(EffectState state,WeaponCharge.Plan plan){return plan.equals(state.charging().get(plan.holder()));}
    static WeaponCharge.Verify prepare(EffectState state,WeaponCharge.Plan plan){
        if(!current(state,plan)||plan.phase()!=WeaponCharge.Phase.CHARGING||state.buffs().timeMicros()!=plan.fullAt())throw new IllegalStateException("Charge is not at its accepted full boundary");
        return new WeaponCharge.Verify(plan);
    }
    static RuleEngine.Local<EffectState> finish(EffectState state,WeaponCharge.Plan plan,WeaponCharge.Verified result,CompiledEffects program){
        if(!current(state,plan))return new RuleEngine.Local<>(state,RuleEngine.Empty.INSTANCE,List.of());
        if(!prepare(state,plan).equals(result.query()))throw new IllegalArgumentException("Charge verification belongs to another plan");
        if(!result.allowed())return cancel(state,plan.holder(),"host_rejected");
        if(!program.checkAction(state,ActionGate.Kind.WEAPON_FIRE,ActionGate.Phase.CONTINUE,plan.event(state.buffs().timeMicros(),true)).allowed())return cancel(state,plan.holder(),"restricted");
        var ready=plan.ready();return new RuleEngine.Local<>(state.cancel(plan.timerId()).withCharge(ready),result,List.of(fact("chorus:weapon_charge_ready",ready,"completed",state.buffs().timeMicros(),true)));
    }
    static RuleEngine.Local<EffectState> reconcile(EffectState state,String holder){
        var plan=state.charging().get(holder);return plan!=null&&!plan.matches(state.equipment().getOrDefault(holder,Loadout.EMPTY))?cancel(state,holder,"equipment_changed"):new RuleEngine.Local<>(state,RuleEngine.Empty.INSTANCE,List.of());
    }
    static RuleEngine.Local<EffectState> cancel(EffectState state,String holder,String reason){
        var plan=state.charging().get(holder);return plan==null?new RuleEngine.Local<>(state,RuleEngine.Empty.INSTANCE,List.of()):ended(state,plan,WeaponCharge.Outcome.CANCELLED,reason);
    }
    private static EffectState clear(EffectState state,WeaponCharge.Plan plan){return state.withoutCharge(plan.holder()).cancel(plan.timerId());}
    private static RuleEngine.Signal fact(String type,WeaponCharge.Plan plan,String reason,long now,boolean held){
        var base=plan.event(now,held);var refs=new HashMap<>(base.references());refs.put("reason",reason);
        return new RuleEngine.Signal(type,new WeaponCharge.Fact(new EffectEvent(base.actor(),base.victim(),base.source(),base.tags(),base.numbers(),base.flags(),refs),plan));
    }
    static void validate(EffectState state,CompiledWeapons weapons){
        for(var plan:state.charging().values()){
            var definition=weapons.definition(plan.gear().definition());long now=state.buffs().timeMicros();
            if(definition==null||definition.charging().isEmpty()||!plan.matches(state.equipment().getOrDefault(plan.holder(),Loadout.EMPTY))||state.handling().containsKey(plan.holder())||state.reloads().containsKey(plan.holder()))throw new IllegalArgumentException("Charge conflicts with current weapon action");
            if(plan.phase()==WeaponCharge.Phase.READY){if(plan.fullAt()>now||state.timers().containsKey(plan.timerId()))throw new IllegalArgumentException("Unfinished charge marked ready");}
            else if(plan.fullAt()<now||plan.fullAt()>now&&!plan.timer().equals(state.timers().get(plan.timerId())))throw new IllegalArgumentException("Charge differs from accepted deadline");
        }
    }
}
