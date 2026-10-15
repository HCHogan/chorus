package com.imdomestic.chorus.effect.buff;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.resource.ResourceAuthority;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Pure relocation of a holder's live buffs and their owned work. The host publishes both domains together. */
public final class BuffTransfer {
    private BuffTransfer() {}
    public static final class Plan {
        private final EffectState source, target, departure, arrival;
        private final Map<EffectState.Lifetime, EffectState.Lifetime> identities;
        private final Map<String, String> timerIds;
        private Plan(EffectState source, EffectState target, EffectState departure, EffectState arrival,
                Map<EffectState.Lifetime, EffectState.Lifetime> identities, Map<String, String> timerIds) {
            this.source=source;this.target=target;this.departure=departure;this.arrival=arrival;
            this.identities=Map.copyOf(identities);this.timerIds=Map.copyOf(timerIds);
        }
        public EffectState departure() { return departure; }
        public EffectState arrival() { return arrival; }
        public Map<EffectState.Lifetime, EffectState.Lifetime> identities() { return identities; }
        public Map<String,String> timerIds() { return timerIds; }
        public void requireCurrent(EffectState source,EffectState target) {
            if(this.source!=source||this.target!=target)throw new IllegalStateException("Buff transfer snapshots changed before publication");
        }
    }
    /** A registered partition already at the destination still participates in a joint character handoff. */
    public static Plan resident(String holder, CompiledEffects program, EffectState state) {
        if(holder==null||holder.isBlank())throw new IllegalArgumentException("Missing resident buff holder");
        for(var buff:state.buffs().instances().values())if(buff.key().holder().equals(holder)) {
            if(!buff.definition().equals(program.buff(buff.definition().id())))throw new IllegalArgumentException("Resident buff definition differs from its catalogue");
            if(buff.activeCount(state.buffs().timeMicros())!=buff.count())throw new IllegalStateException("Resident buffs require settled expiry");
        }
        return new Plan(state,state,state,state,Map.of(),Map.of());
    }
    /** No gained/ended/timer facts are replayed. Historical observations and lexical results stay at their observation time. */
    public static Plan prepare(String holder,CompiledEffects sourceProgram,EffectState source,CompiledEffects targetProgram,EffectState target) {
        return prepare(holder,sourceProgram,source,targetProgram,target,Optional.empty());
    }
    public static Plan prepare(String holder,CompiledEffects sourceProgram,EffectState source,CompiledEffects targetProgram,EffectState target,Optional<String> sourceDomain) {
        sourceDomain.ifPresent(domain->new ResourceAuthority.Lease(domain,1));
        if(holder==null||holder.isBlank()||source==target)throw new IllegalArgumentException("Buff transfer needs a holder and distinct domains");
        if(!sourceProgram.program().equals(targetProgram.program()))throw new IllegalArgumentException("Buff transfer requires the same pinned catalogue");
        var outgoing=new TreeMap<>(source.buffs().instances());var incoming=new TreeMap<>(target.buffs().instances());
        var identities=new HashMap<EffectState.Lifetime,EffectState.Lifetime>();long generation=target.buffs().nextGeneration();
        for(var buff:source.buffs().instances().values())if(buff.key().holder().equals(holder)) {
            if(!buff.definition().equals(sourceProgram.buff(buff.definition().id())))throw new IllegalArgumentException("Buff transfer definition differs from its catalogue");
            if(buff.activeCount(source.buffs().timeMicros())!=buff.count())throw new IllegalStateException("Buff transfer requires settled expiry");
            if(incoming.containsKey(buff.key()))throw new IllegalStateException("Destination already has this buff key");
            var identity=new EffectState.Lifetime(buff.key(),generation);generation=Math.incrementExact(generation);
            identities.put(new EffectState.Lifetime(buff.key(),buff.generation()),identity);
            var stacks=buff.stacks().stream().map(stack->new BuffInstance.Stack(stack.id(),
                    deadline(stack.expiresAt(),buff.pausedAt().orElse(source.buffs().timeMicros()),target.buffs().timeMicros()),stack.origin())).toList();
            var moved=new BuffInstance(identity.generation(),buff.key(),buff.definition(),buff.origin(),stacks,buff.nextStackId(),buff.tier(),
                    buff.originalDurationMicros(),buff.longestDurationMicros(),buff.pausedAt().isPresent()?OptionalLong.of(target.buffs().timeMicros()):OptionalLong.empty(),buff.components(),buff.quantity());
            incoming.put(moved.key(),moved);outgoing.remove(buff.key());
        }
        if(identities.isEmpty())return new Plan(source,target,source,target,identities,Map.of());
        var oldTimers=new TreeMap<>(source.timers());var newTimers=new TreeMap<>(target.timers());var timerIds=new TreeMap<String,String>();int serial=0;
        for(var timer:source.timers().values()) {
            var identity=timer.lifetime().map(identities::get).orElse(null);if(identity==null)continue;
            String id="relocated/buff/"+identity.generation()+"/"+serial++;var signal=timer.signal();
            if(signal.payload() instanceof EffectTimers.Scheduled scheduled) {
                var owner=new EffectTimers.Owner(Optional.empty(),Optional.of(identity));id=owner.key(scheduled.name());
                signal=new RuleEngine.Signal(signal.type(),new EffectTimers.Scheduled(scheduled.name(),owner,scheduled.event()));
            }else if(signal.payload() instanceof EffectContinuations.Pending pending) {
                if(!(pending.scope() instanceof BuffRules.Scope scope)||!scope.identity().equals(timer.lifetime().orElseThrow()))
                    throw new IllegalArgumentException("Moved continuation is not bound to its buff lifetime");
                var owner=new EffectTimers.Owner(Optional.empty(),Optional.of(identity));
                signal=new RuleEngine.Signal(signal.type(),pending.relocated(id,scope.relocated(identity),owner,target.buffs().timeMicros(),sourceDomain,targetProgram.resourceRouting()));
            }
            var moved=new EffectState.Timer(id,timer.pausedRemaining().isPresent()?Long.MAX_VALUE:deadline(timer.dueAt(),source.buffs().timeMicros(),target.buffs().timeMicros()),
                    timer.intervalMicros(),timer.remaining(),signal,Optional.of(identity),timer.pausedRemaining());
            if(newTimers.putIfAbsent(id,moved)!=null)throw new IllegalStateException("Destination already has the relocated timer identity");
            oldTimers.remove(timer.id());timerIds.put(timer.id(),id);
        }
        var departure=copy(source,new BuffStore(source.buffs().timeMicros(),source.buffs().nextGeneration(),outgoing),oldTimers);
        var arrival=copy(target,new BuffStore(target.buffs().timeMicros(),generation,incoming),newTimers);
        return new Plan(source,target,departure,arrival,identities,timerIds);
    }
    private static long deadline(long due,long oldClock,long newClock) {
        if(due==BuffDefinition.FOREVER)return due;
        long remaining=Math.subtractExact(due,oldClock);
        if(remaining<=0)throw new IllegalStateException("Buff transfer requires a future deadline");
        long result=Math.addExact(newClock,remaining);
        if(result==BuffDefinition.FOREVER)throw new IllegalArgumentException("Finite relocated deadline cannot become forever");
        return result;
    }
    private static EffectState copy(EffectState state,BuffStore buffs,Map<String,EffectState.Timer> timers) {
        return new EffectState(buffs,state.resources(),timers,state.sources(),state.mode(),state.equipment(),state.abilities(),state.ammunition(),state.reloads(),
                state.shots(),state.random(),state.shotGroups(),state.damageGroups(),state.retainedCosts(),state.damageTallies(),state.handling(),state.aiming(),state.attachments(),state.charging(),state.actors());
    }
}
