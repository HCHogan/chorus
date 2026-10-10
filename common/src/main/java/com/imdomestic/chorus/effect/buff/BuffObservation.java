package com.imdomestic.chorus.effect.buff;

import java.util.*;

/** Immutable presence at a fact's observation boundary, independent of subsequent reactions or expiry. */
public record BuffObservation(long timeMicros, Map<String, List<Entry>> holders) {
    public record Entry(BuffInstance.Key key, long generation, BuffInstance.Origin origin, Set<String> tags, int stacks, int tier, boolean paused) {
        public Entry {
            Objects.requireNonNull(key); Objects.requireNonNull(origin); tags=Set.copyOf(tags);
            if(generation<1||stacks<1||tier<1)throw new IllegalArgumentException("Invalid observed buff");
        }
    }
    public BuffObservation {
        if(timeMicros<0||timeMicros==BuffDefinition.FOREVER)throw new IllegalArgumentException("Invalid observation time");
        var copy=new TreeMap<String,List<Entry>>();
        holders.forEach((holder,entries)->{
            if(holder==null||holder.isBlank())throw new IllegalArgumentException("Missing observed holder");
            var identities=new HashSet<BuffInstance.Key>();
            for(var entry:entries)if(!entry.key().holder().equals(holder)||!identities.add(entry.key()))throw new IllegalArgumentException("Foreign or duplicate observed buff");
            copy.put(holder,entries.stream().sorted(Comparator.comparing(Entry::key)).toList());
        });
        holders=Collections.unmodifiableMap(copy);
    }
    public boolean available(String holder){return holders.containsKey(holder);}
    public List<Entry> require(String holder){
        var entries=holders.get(holder);
        if(entries==null)throw new IllegalArgumentException("No event buff observation for holder: "+holder);
        return entries;
    }
    /** A sampled empty list means known absence; an omitted holder is unknown. Paused presence is retained. */
    public static BuffObservation capture(BuffStore store,String... selected){
        var entries=new TreeMap<String,List<Entry>>();
        for(String holder:selected)if(!holder.isBlank())entries.putIfAbsent(holder,new ArrayList<>());
        for(var instance:store.instances().values()){
            var destination=entries.get(instance.key().holder());int count=instance.activeCount(store.timeMicros());
            if(destination!=null&&count>0)destination.add(new Entry(instance.key(),instance.generation(),instance.origin(),instance.definition().tags(),count,instance.tier(),instance.pausedAt().isPresent()));
        }
        return new BuffObservation(store.timeMicros(),entries);
    }
}
