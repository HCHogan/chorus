package com.imdomestic.chorus.effect.buff;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;

/** Select once, then remove every matching instance before publishing any lifecycle reaction. */
public final class BuffRemoval {
    private BuffRemoval() {}
    public record Receipt(String holder,String tag,BuffInstance.Origin cause,List<BuffInstance> removed)
            implements RuleEngine.ActionResult,EffectEvent.Carrier {
        public Receipt {
            Objects.requireNonNull(holder);Objects.requireNonNull(tag);Objects.requireNonNull(cause);removed=List.copyOf(removed);
            if(holder.isBlank()||tag.isBlank())throw new IllegalArgumentException("Buff removal requires holder and tag");
            var keys=new HashSet<BuffInstance.Key>();
            for(var buff:removed)if(!buff.key().holder().equals(holder)||!buff.definition().tags().contains(tag)||!keys.add(buff.key()))
                throw new IllegalArgumentException("Removal receipt differs from its selection");
        }
        public long stacks(){long total=0;for(var buff:removed)total=Math.addExact(total,buff.count());return total;}
        public boolean changed(){return !removed.isEmpty();}
        @Override public EffectEvent event(){
            return new EffectEvent(cause.owner(),holder,cause,Set.of("chorus:buff_removal"),
                    Map.of("instances",new Measure(removed.size(),Unit.COUNT),"stacks",new Measure(stacks(),Unit.COUNT)),
                    Map.of("changed",changed()),Map.of("tag",tag));
        }
    }
    public record Result(BuffStore store,Receipt receipt,List<RuleEngine.Signal> signals){
        public Result{Objects.requireNonNull(store);Objects.requireNonNull(receipt);signals=List.copyOf(signals);}
    }
    public static Result remove(BuffStore store,String holder,String tag,BuffInstance.Origin cause){
        Buffs.settled(store);
        var selected=store.instances().values().stream().filter(b->b.key().holder().equals(holder)&&b.definition().tags().contains(tag))
                .sorted(Comparator.comparing(BuffInstance::key)).toList();
        var receipt=new Receipt(holder,tag,cause,selected);var current=store;var signals=new ArrayList<RuleEngine.Signal>();
        for(var buff:selected){var result=Buffs.remove(current,buff.key(),Buffs.Reason.REMOVED);current=result.store();signals.addAll(result.signals());}
        if(receipt.changed())signals.add(new RuleEngine.Signal("chorus:buffs_removed",receipt));
        return new Result(current,receipt,signals);
    }
}
