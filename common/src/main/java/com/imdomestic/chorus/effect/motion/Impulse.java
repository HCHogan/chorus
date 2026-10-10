package com.imdomestic.chorus.effect.motion;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** One additive velocity change, in meters per second. It does not promise a displacement. */
public final class Impulse {
    private Impulse() {}
    public static final Unit SPEED = new Unit("chorus:meter_per_second");
    public record Velocity(double x, double y, double z) {
        public Velocity { Numbers.finite(x,"velocity x"); Numbers.finite(y,"velocity y"); Numbers.finite(z,"velocity z"); }
        public Velocity add(Velocity other) { return new Velocity(x+other.x,y+other.y,z+other.z); }
        public Velocity subtract(Velocity other) { return new Velocity(x-other.x,y-other.y,z-other.z); }
    }
    /** World-axis multipliers applied after normalizing the captured direction. No second normalization. */
    public record Scale(double x, double y, double z) {
        public static final Scale ONE = new Scale(1,1,1);
        public Scale { Numbers.finite(x,"impulse x scale"); Numbers.finite(y,"impulse y scale"); Numbers.finite(z,"impulse z scale"); }
    }
    public record Command(String target, Optional<WorldDirection> direction, double speed, Scale scale,
            BuffInstance.Origin origin, Set<String> tags) implements RuleEngine.WorldCommand {
        public Command {
            if(target==null||target.isBlank())throw new IllegalArgumentException("Missing impulse target");
            Objects.requireNonNull(direction); Objects.requireNonNull(scale); Objects.requireNonNull(origin); tags=Set.copyOf(tags);
            Numbers.nonnegative(speed,"impulse speed");
            tags.forEach(tag->{if(!tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid impulse tag");});
            direction.ifPresent(d->vector(d,speed,scale));
        }
        public Optional<Velocity> delta() { return direction.map(d->vector(d,speed,scale)); }
        private static Velocity vector(WorldDirection d,double speed,Scale scale) { return new Velocity(d.x()*speed*scale.x(),d.y()*speed*scale.y(),d.z()*speed*scale.z()); }
    }
    public enum Outcome { APPLIED, UNCHANGED, MISSING_TARGET, DEAD, SPECTATOR, PASSENGER, SLEEPING, MISSING_DIRECTION, WRONG_DIMENSION, UNLOADED }
    public record Change(Velocity before, Velocity after) {
        public Change { Objects.requireNonNull(before); Objects.requireNonNull(after); after.subtract(before); }
        public boolean changed() { return before.x()!=after.x()||before.y()!=after.y()||before.z()!=after.z(); }
        public Velocity delta() { return after.subtract(before); }
    }
    public record Receipt(Command command, Outcome outcome, Optional<Change> change) implements RuleEngine.ActionResult {
        public Receipt {
            Objects.requireNonNull(command); Objects.requireNonNull(outcome); Objects.requireNonNull(change);
            boolean observed=outcome==Outcome.APPLIED||outcome==Outcome.UNCHANGED;
            if(observed!=change.isPresent()||(observed&&change.orElseThrow().changed()!=(outcome==Outcome.APPLIED)))
                throw new IllegalArgumentException("Inconsistent impulse outcome");
        }
        public static Receipt rejected(Command command,Outcome outcome) { return new Receipt(command,outcome,Optional.empty()); }
        public Change requireChange() { return change.orElseThrow(()->new IllegalArgumentException("Impulse has no velocity observation: "+outcome)); }
    }
    public record Applied(EffectEvent event,Receipt receipt) implements EffectEvent.Carrier {
        public Applied { Objects.requireNonNull(event); Objects.requireNonNull(receipt); if(receipt.outcome()!=Outcome.APPLIED)throw new IllegalArgumentException("Impulse was not applied"); }
    }
}
