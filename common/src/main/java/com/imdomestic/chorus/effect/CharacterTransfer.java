package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.effect.ability.AbilityLoadout;
import com.imdomestic.chorus.effect.actor.ActorBinding;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceAuthority;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Character-owned state, separate from buff relocation and world-bound attacks, attachments and accounting. */
public final class CharacterTransfer {
    private CharacterTransfer() {}
    public static final class Plan {
        private final EffectState source,target,departure,arrival;
        private final Map<String,String> timerIds;
        private Plan(EffectState source,EffectState target,EffectState departure,EffectState arrival,Map<String,String> timerIds) {
            this.source=source;this.target=target;this.departure=departure;this.arrival=arrival;this.timerIds=Map.copyOf(timerIds);
        }
        public EffectState departure(){return departure;}
        public EffectState arrival(){return arrival;}
        public Map<String,String> timerIds(){return timerIds;}
        public void requireCurrent(EffectState source,EffectState target){if(this.source!=source||this.target!=target)throw new IllegalStateException("Character transfer snapshots changed");}
    }
    /** carriedGear comes from the authoritative physical containers, including unequipped carried weapons. */
    public static Plan prepare(String holder,Set<String> gearInstances,CompiledEffects sourceProgram,EffectState source,
            CompiledEffects targetProgram,EffectState target,Optional<String> sourceDomain) {
        if(holder==null||holder.isBlank()||source==target)throw new IllegalArgumentException("Character transfer needs a holder and distinct domains");
        sourceDomain.ifPresent(domain->new ResourceAuthority.Lease(domain,1));var carriedGear=Set.copyOf(gearInstances);
        if(carriedGear.stream().anyMatch(String::isBlank))throw new IllegalArgumentException("Missing carried gear identity");
        if(!sourceProgram.program().equals(targetProgram.program()))throw new IllegalArgumentException("Character transfer requires the same pinned catalogue");
        var loadout=source.equipment().getOrDefault(holder,Loadout.EMPTY);
        var equipped=new HashSet<String>();loadout.slots().values().forEach(gear->equipped.add(gear.instance()));
        if(!carriedGear.containsAll(equipped))throw new IllegalArgumentException("Carried gear omits equipped instances");
        for(var entry:source.equipment().entrySet())if(!entry.getKey().equals(holder))rejectSharedGear(entry.getValue(),carriedGear);
        target.equipment().values().forEach(other->rejectSharedGear(other,carriedGear));
        var a=new Side(source);var b=new Side(target);var ids=new TreeMap<String,String>();long from=source.buffs().timeMicros(),to=target.buffs().timeMicros();
        move(holder,a.equipment,b.equipment);move(holder,a.abilities,b.abilities);move(holder,a.actors,b.actors);
        var movingSources=new TreeMap<String,EffectSource>();
        for(var entry:source.sources().entrySet())if(entry.getValue().holder().equals(holder)){
            move(entry.getKey(),a.sources,b.sources);movingSources.put(entry.getKey(),entry.getValue());
        }
        for(var gear:carriedGear){
            move(gear,a.ammunition,b.ammunition);var shot=move(gear,a.shots,b.shots);if(shot!=null)b.shots.put(gear,shot.relocated(from,to));
        }
        var reload=move(holder,a.reloads,b.reloads);
        if(reload!=null){requireGear(carriedGear,reload.gear());if(reload.phase()!=WeaponReload.Phase.WAITING)throw new IllegalStateException("Reload insertion must settle before transfer");
            var moved=reload.relocated(from,to);b.reloads.put(holder,moved);timer(a,b,reload.timer(),moved.timer(),from,ids);}
        var handling=move(holder,a.handling,b.handling);
        if(handling!=null){requireGear(carriedGear,handling.gear());handling.target().ifPresent(gear->requireGear(carriedGear,gear));
            if(handling.completed())throw new IllegalStateException("Handling phase must settle before transfer");
            var moved=handling.relocated(from,to);b.handling.put(holder,moved);timer(a,b,handling.timer(),moved.timer(),from,ids);}
        var aim=move(holder,a.aiming,b.aiming);
        if(aim!=null){requireGear(carriedGear,aim.gear());var moved=aim.relocated(from,to);b.aiming.put(holder,moved);
            if(aim.phase()!=WeaponAim.Phase.AIMED)timer(a,b,aim.timer(),moved.timer(),from,ids);}
        var charge=move(holder,a.charging,b.charging);
        if(charge!=null){requireGear(carriedGear,charge.gear());var moved=charge.relocated(from,to);b.charging.put(holder,moved);
            if(charge.phase()!=WeaponCharge.Phase.READY)timer(a,b,charge.timer(),moved.timer(),from,ids);}
        int serial=0;
        for(var old:source.timers().values()){
            var owner=switch(old.signal().payload()){
                case EffectTimers.Scheduled scheduled->Optional.of(scheduled.owner());
                case EffectContinuations.Pending pending->pending.owner();
                default->Optional.<EffectTimers.Owner>empty();
            };
            var root=owner.flatMap(EffectTimers.Owner::source);
            if(root.isEmpty()||!root.orElseThrow().equals(movingSources.get(root.orElseThrow().instance())))continue;
            if(old.lifetime().isPresent()||old.pausedRemaining().isPresent())throw new IllegalArgumentException("Source timer has a buff lifetime");
            var signal=old.signal();String id=old.id();
            if(signal.payload() instanceof EffectContinuations.Pending pending){
                do{id="relocated/character/"+holder.length()+":"+holder+"/"+serial++;}while(b.timers.containsKey(id));
                signal=new RuleEngine.Signal(signal.type(),pending.relocated(id,pending.scope(),owner.orElseThrow(),to,sourceDomain,targetProgram.resourceRouting()));
            }
            long due=WeaponPhaseTime.local(old.dueAt(),Math.subtractExact(to,from));
            var moved=new EffectState.Timer(id,due,old.intervalMicros(),old.remaining(),signal,Optional.empty());
            timer(a,b,old,moved,from,ids);
        }
        return new Plan(source,target,a.finish(),b.finish(),ids);
    }
    private static void rejectSharedGear(Loadout other,Set<String> carried){if(other.slots().values().stream().anyMatch(g->carried.contains(g.instance())))throw new IllegalStateException("Carried gear is also equipped by another owner or domain");}
    private static void requireGear(Set<String> carried,Loadout.Gear gear){if(!carried.contains(gear.instance()))throw new IllegalArgumentException("Active weapon phase refers to uncarried gear");}
    private static <T>T move(String key,Map<String,T>a,Map<String,T>b){
        if(b.containsKey(key))throw new IllegalStateException("Destination already owns transferred identity: "+key);
        var value=a.remove(key);if(value!=null)b.put(key,value);return value;
    }
    private static void timer(Side a,Side b,EffectState.Timer before,EffectState.Timer after,long from,Map<String,String> ids){
        if(before.dueAt()<=from||!before.equals(a.timers.get(before.id())))throw new IllegalStateException("Transferred phase does not own a settled future timer");
        if(b.timers.putIfAbsent(after.id(),after)!=null)throw new IllegalStateException("Destination already owns transferred timer");
        a.timers.remove(before.id());ids.put(before.id(),after.id());
    }
    private static final class Side {
        final EffectState original;
        final Map<String,Loadout> equipment;final Map<String,AbilityLoadout> abilities;final Map<String,ActorBinding> actors;
        final Map<String,EffectSource> sources;final Map<String,AmmoState> ammunition;final Map<String,WeaponFire.Shot> shots;
        final Map<String,WeaponReload.Plan> reloads;final Map<String,WeaponHandling.Plan> handling;final Map<String,WeaponAim.Plan> aiming;
        final Map<String,WeaponCharge.Plan> charging;final Map<String,EffectState.Timer> timers;
        Side(EffectState state){original=state;equipment=new TreeMap<>(state.equipment());abilities=new TreeMap<>(state.abilities());actors=new TreeMap<>(state.actors());
            sources=new TreeMap<>(state.sources());ammunition=new TreeMap<>(state.ammunition());shots=new TreeMap<>(state.shots());reloads=new TreeMap<>(state.reloads());
            handling=new TreeMap<>(state.handling());aiming=new TreeMap<>(state.aiming());charging=new TreeMap<>(state.charging());timers=new TreeMap<>(state.timers());}
        EffectState finish(){var s=original;return new EffectState(s.buffs(),s.resources(),timers,sources,s.mode(),equipment,abilities,ammunition,reloads,shots,s.random(),
                s.shotGroups(),s.damageGroups(),s.retainedCosts(),s.damageTallies(),handling,aiming,s.attachments(),charging,actors);}
    }
}
