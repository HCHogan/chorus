package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.ammo.AmmoCapacity;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Read-only numerical dependencies; deliberately unrelated to event roots or effect chains. */
public record NumericQuery(EffectEvent event, Path path) implements EffectEvent.Carrier {
    public NumericQuery { Objects.requireNonNull(event); Objects.requireNonNull(path); }
    public record Attribute(String holder,String profile) {
        public Attribute { if(holder==null||holder.isBlank()||profile==null||profile.isBlank())throw new IllegalArgumentException("Missing attribute query identity"); }
    }
    public record Path(Set<String> ammunition,Set<Attribute> attributes) {
        public Path { ammunition=Set.copyOf(ammunition);attributes=Set.copyOf(attributes); }
        public static Path empty(){return new Path(Set.of(),Set.of());}
        public static Path from(RuleEngine.Payload payload) {
            if(payload instanceof NumericQuery query)return query.path();
            if(payload instanceof AmmoCapacity.Query query)return new Path(query.dependencies(),Set.of());
            return empty();
        }
        public Path ammo(String weapon) {
            var next=new HashSet<>(ammunition);
            if(!next.add(weapon))throw new IllegalArgumentException("Circular ammunition capacity expression: "+weapon);
            return new Path(next,attributes);
        }
        public Path attribute(String holder,String profile) {
            var key=new Attribute(holder,profile);var next=new HashSet<>(attributes);
            if(!next.add(key))throw new IllegalArgumentException("Circular attribute expression: "+holder+" / "+profile);
            return new Path(ammunition,next);
        }
    }
}
