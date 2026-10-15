package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Aim input is accepted once per edge; each edge samples its own duration. */
final class CompiledAiming {
    private CompiledAiming() {}
    static RuleEngine.Local<EffectState> begin(EffectState state,WeaponAim.Request request,CompiledWeapons weapons,WeaponQueries program) {
        var previous=state.aiming().get(request.holder());
        if(previous==null&&!request.pressed()||previous!=null&&previous.held()==request.pressed())return reject(state,WeaponAim.Outcome.UNCHANGED);
        var loadout=state.equipment().getOrDefault(request.holder(),Loadout.EMPTY);var gear=WeaponReload.drawn(loadout);
        if(gear.isEmpty())return reject(state,WeaponAim.Outcome.EMPTY_HANDS);
        var definition=weapons.definition(gear.orElseThrow().definition());
        if(definition==null||definition.aiming().isEmpty())return reject(state,WeaponAim.Outcome.NOT_CONFIGURED);
        if(request.pressed()&&(state.handling().containsKey(request.holder())||state.reloads().containsKey(request.holder())))return reject(state,WeaponAim.Outcome.BUSY);
        var phase=request.pressed()?WeaponAim.Phase.ENTERING:WeaponAim.Phase.EXITING;
        var tags=new HashSet<>(weapons.itemTags(gear.orElseThrow().definition()));tags.add(request.pressed()?"chorus:weapon_aim_enter":"chorus:weapon_aim_exit");
        var origin=new BuffInstance.Origin(request.holder(),gear.orElseThrow().instance(),gear.orElseThrow().instance(),"",tags);
        var query=query(request.holder(),request.token(),gear.orElseThrow(),origin,phase);
        if(request.pressed()) {
            var decision=program.checkAction(state,ActionGate.Kind.WEAPON_AIM,ActionGate.Phase.START,query);
            if(!decision.allowed())return new RuleEngine.Local<>(state,new WeaponAim.Receipt(WeaponAim.Outcome.RESTRICTED,Optional.empty(),Optional.of(decision)),List.of());
        }
        var timing=request.pressed()?definition.aiming().orElseThrow().enter():definition.aiming().orElseThrow().exit();
        var event=new RuleEngine.Event(0,0,Optional.empty(),state.buffs().timeMicros(),new RuleEngine.Signal("chorus:internal/aim_query",query));
        var evaluation=program.evaluation(state,new RuleEngine.Context(event,"aim/"+request.token(),new WeaponAim.Scope(query),Map.of()),Map.of());
        var input=timing.value().evaluate(evaluation);var calculation=timing.profiles().isEmpty()?Optional.<CalculationPipeline.Result>empty():Optional.of(program.calculatePipeline(state,request.holder(),query,timing.profiles(),input));
        var duration=calculation.map(CalculationPipeline.Result::output).orElse(input);long now=state.buffs().timeMicros();
        var plan=new WeaponAim.Plan(request.holder(),request.token(),loadout.drawn().orElseThrow(),gear.orElseThrow(),origin,phase,now,Math.addExact(now,WeaponHandling.micros(duration)),input,duration,calculation);
        var updated=previous==null?state:clear(state,previous);updated=updated.withAim(plan);
        var facts=new ArrayList<RuleEngine.Signal>();facts.add(fact(request.pressed()?"chorus:weapon_aim_started":"chorus:weapon_aim_released",plan,"input",now));
        if(plan.dueTime()==now)facts.add(new RuleEngine.Signal(WeaponAim.DUE,plan));else updated=updated.schedule(plan.timer());
        return new RuleEngine.Local<>(updated,new WeaponAim.Receipt(WeaponAim.Outcome.ACCEPTED,Optional.of(plan),Optional.empty()),facts);
    }
    private static RuleEngine.Local<EffectState> reject(EffectState state,WeaponAim.Outcome outcome){return new RuleEngine.Local<>(state,new WeaponAim.Receipt(outcome,Optional.empty(),Optional.empty()),List.of());}
    static boolean current(EffectState state,WeaponAim.Plan plan){return plan.equals(state.aiming().get(plan.holder()));}
    static WeaponAim.Verify prepare(EffectState state,WeaponAim.Plan plan){
        if(!current(state,plan)||plan.phase()==WeaponAim.Phase.AIMED||state.buffs().timeMicros()!=plan.dueTime())throw new IllegalStateException("Aim is not at its accepted boundary");
        return new WeaponAim.Verify(plan);
    }
    static RuleEngine.Local<EffectState> finish(EffectState state,WeaponAim.Plan plan,WeaponAim.Verified result,WeaponQueries program) {
        if(!current(state,plan))return new RuleEngine.Local<>(state,RuleEngine.Empty.INSTANCE,List.of());
        if(!prepare(state,plan).equals(result.query()))throw new IllegalArgumentException("Aim receipt belongs to another transition");
        if(!result.allowed())return cancel(state,plan.holder(),"host_rejected");
        if(plan.phase()==WeaponAim.Phase.EXITING)return new RuleEngine.Local<>(clear(state,plan),result,List.of(fact("chorus:weapon_aim_ended",plan,"released",state.buffs().timeMicros())));
        var decision=program.checkAction(state,ActionGate.Kind.WEAPON_AIM,ActionGate.Phase.COMPLETE,query(plan.holder(),plan.token(),plan.gear(),plan.origin(),plan.phase()));
        if(!decision.allowed())return cancel(state,plan.holder(),"restricted");
        var aimed=plan.aimed();return new RuleEngine.Local<>(state.cancel(plan.timerId()).withAim(aimed),result,List.of(fact("chorus:weapon_aim_ready",aimed,"completed",state.buffs().timeMicros())));
    }
    static RuleEngine.Local<EffectState> reconcile(EffectState state,String holder) {
        var plan=state.aiming().get(holder);
        return plan!=null&&!plan.matches(state.equipment().getOrDefault(holder,Loadout.EMPTY))?cancel(state,holder,"equipment_changed"):new RuleEngine.Local<>(state,RuleEngine.Empty.INSTANCE,List.of());
    }
    static RuleEngine.Local<EffectState> cancel(EffectState state,String holder,String reason) {
        var plan=state.aiming().get(holder);
        return plan==null?new RuleEngine.Local<>(state,RuleEngine.Empty.INSTANCE,List.of()):new RuleEngine.Local<>(clear(state,plan),RuleEngine.Empty.INSTANCE,List.of(fact("chorus:weapon_aim_ended",plan,reason,state.buffs().timeMicros())));
    }
    private static EffectState clear(EffectState state,WeaponAim.Plan plan){return state.withoutAim(plan.holder()).cancel(plan.timerId());}
    static void validate(EffectState state) {
        for(var plan:state.aiming().values()) {
            long now=state.buffs().timeMicros();
            if(!plan.matches(state.equipment().getOrDefault(plan.holder(),Loadout.EMPTY))||state.handling().containsKey(plan.holder())||state.reloads().containsKey(plan.holder()))throw new IllegalArgumentException("Aim conflicts with current equipment or weapon action");
            if(plan.phase()==WeaponAim.Phase.AIMED) {
                if(plan.dueTime()>now||state.timers().containsKey(plan.timerId()))throw new IllegalArgumentException("Unfinished aim marked ready");
            }else if(plan.dueTime()<now||plan.dueTime()>now&&!plan.timer().equals(state.timers().get(plan.timerId())))throw new IllegalArgumentException("Aim differs from its accepted deadline");
        }
    }
    private static EffectEvent query(String holder,String token,Loadout.Gear gear,BuffInstance.Origin origin,WeaponAim.Phase phase) {
        return new EffectEvent(holder,gear.instance(),origin,origin.tags(),Map.of(),Map.of("aim_held",phase!=WeaponAim.Phase.EXITING,"aim_ready",phase==WeaponAim.Phase.AIMED),
                Map.of("weapon",gear.instance(),"item",gear.definition(),"aim",token,"aim_phase",phase.name().toLowerCase(Locale.ROOT)));
    }
    private static RuleEngine.Signal fact(String type,WeaponAim.Plan plan,String reason,long now) {
        var base=query(plan.holder(),plan.token(),plan.gear(),plan.origin(),plan.phase());var refs=new HashMap<>(base.references());refs.put("reason",reason);
        var flags=new HashMap<>(base.flags());if(type.equals("chorus:weapon_aim_ended")){flags.put("aim_held",false);flags.put("aim_ready",false);}
        var event=new EffectEvent(base.actor(),base.victim(),base.source(),base.tags(),Map.of("duration",plan.duration(),"elapsed",new Measure(WeaponPhaseTime.elapsed(now,plan.startTime())/1_000_000.0,Unit.SECOND)),flags,refs);
        return new RuleEngine.Signal(type,new WeaponAim.Fact(event,plan));
    }
}
