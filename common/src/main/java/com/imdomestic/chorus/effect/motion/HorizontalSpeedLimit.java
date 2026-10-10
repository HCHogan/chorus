package com.imdomestic.chorus.effect.motion;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Independent ceilings take the minimum. Absence means unconstrained; zero is an actual restriction. */
public final class HorizontalSpeedLimit {
    private HorizontalSpeedLimit() {}
    public record Declaration(String id,Value speed,Condition condition){
        public Declaration{EffectTimers.localName(id);Objects.requireNonNull(speed);Objects.requireNonNull(condition);}
        public void validate(Validation v){condition.validate(v);Validation.same(speed.unit(v),Unit.METER_PER_SECOND);if(speed instanceof Value.Constant c)Numbers.nonnegative(c.value(),"horizontal speed limit");}
    }
    public record Contribution(String declaration,String bundle,String instance,BuffInstance.Origin origin,double metersPerSecond){
        public Contribution{Objects.requireNonNull(declaration);Objects.requireNonNull(bundle);Objects.requireNonNull(instance);Objects.requireNonNull(origin);Numbers.nonnegative(metersPerSecond,"horizontal speed limit");}
    }
    public record Decision(EffectEvent query,List<Contribution> contributions){
        public Decision{Objects.requireNonNull(query);contributions=List.copyOf(contributions);}
        public OptionalDouble maximum(){return contributions.stream().mapToDouble(Contribution::metersPerSecond).min();}
    }
}
