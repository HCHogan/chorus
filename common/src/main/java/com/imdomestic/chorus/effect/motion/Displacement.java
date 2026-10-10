package com.imdomestic.chorus.effect.motion;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** One collision-resolved, authoritative relocation. Distance is in meters, not velocity or a promised trajectory. */
public final class Displacement {
    private Displacement() {}
    public record Offset(double x,double y,double z){
        public Offset{Numbers.finite(x,"offset x");Numbers.finite(y,"offset y");Numbers.finite(z,"offset z");Numbers.finite(Math.hypot(Math.hypot(x,y),z),"offset length");}
        public double length(){return Math.hypot(Math.hypot(x,y),z);}
    }
    public record Command(String target,Optional<WorldDirection> direction,double distance,BuffInstance.Origin origin,Set<String> tags) implements RuleEngine.WorldCommand {
        public Command{
            if(target==null||target.isBlank())throw new IllegalArgumentException("Missing displacement target");Objects.requireNonNull(direction);Objects.requireNonNull(origin);tags=Set.copyOf(tags);Numbers.nonnegative(distance,"displacement distance");
            tags.forEach(tag->{if(!tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid displacement tag");});
            direction.ifPresent(d->new Offset(d.x()*distance,d.y()*distance,d.z()*distance));
        }
        public Optional<Offset> requested(){return direction.map(d->new Offset(d.x()*distance,d.y()*distance,d.z()*distance));}
    }
    public enum Outcome{APPLIED,UNCHANGED,MISSING_TARGET,DEAD,SPECTATOR,PASSENGER,VEHICLE,SLEEPING,NO_PHYSICS,MISSING_DIRECTION,WRONG_DIMENSION,UNLOADED,QUERY_TOO_LARGE,OUT_OF_BOUNDS}
    public record Change(WorldPosition before,WorldPosition after,Offset resolved){
        public Change{Objects.requireNonNull(before);Objects.requireNonNull(after);Objects.requireNonNull(resolved);if(!before.dimension().equals(after.dimension()))throw new IllegalArgumentException("Displacement cannot change dimensions");new Offset(after.x()-before.x(),after.y()-before.y(),after.z()-before.z());}
        public Offset delta(){return new Offset(after.x()-before.x(),after.y()-before.y(),after.z()-before.z());}
        public boolean changed(){return before.x()!=after.x()||before.y()!=after.y()||before.z()!=after.z();}
    }
    public record Receipt(Command command,Outcome outcome,Optional<Change> change) implements PositionResult {
        public Receipt{
            Objects.requireNonNull(command);Objects.requireNonNull(outcome);Objects.requireNonNull(change);boolean observed=outcome==Outcome.APPLIED||outcome==Outcome.UNCHANGED;
            if(observed!=change.isPresent()||(observed&&(command.direction().isEmpty()||change.get().changed()!=(outcome==Outcome.APPLIED)||!command.direction().get().dimension().equals(change.get().before().dimension()))))throw new IllegalArgumentException("Inconsistent displacement receipt");
        }
        public static Receipt rejected(Command command,Outcome outcome){return new Receipt(command,outcome,Optional.empty());}
        public Change requireChange(){return change.orElseThrow(()->new IllegalArgumentException("Displacement has no position observation: "+outcome));}
        public boolean clipped(){if(change.isEmpty())return false;var requested=command.requested().orElseThrow();var resolved=change.get().resolved();return requested.x()!=resolved.x()||requested.y()!=resolved.y()||requested.z()!=resolved.z();}
        @Override public Optional<WorldPosition> position(){return change.map(Change::after);}
    }
    public record Applied(EffectEvent event,Receipt receipt) implements EffectEvent.Carrier {
        public Applied{Objects.requireNonNull(event);Objects.requireNonNull(receipt);if(receipt.outcome()!=Outcome.APPLIED)throw new IllegalArgumentException("Displacement was not applied");}
    }
}
