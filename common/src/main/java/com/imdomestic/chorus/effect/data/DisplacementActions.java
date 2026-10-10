package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.function.Predicate;

public final class DisplacementActions {
    private DisplacementActions() {}
    public static final ResultShape RESULT;
    static{
        var fields=new HashMap<String,ResultShape.Field>();
        for(String phase:List.of("before","after","requested","resolved","delta"))for(String axis:List.of("x","y","z"))fields.put(phase+"_"+axis,new ResultShape.Field(Unit.METER,r->component(vector((Displacement.Receipt)r,phase),axis)));
        for(String phase:List.of("requested","resolved","delta"))fields.put(phase+"_distance",new ResultShape.Field(Unit.METER,r->vector((Displacement.Receipt)r,phase).length()));
        var flags=new HashMap<String,Predicate<RuleEngine.ActionResult>>();
        for(var outcome:Displacement.Outcome.values())flags.put(outcome.name().toLowerCase(Locale.ROOT),r->((Displacement.Receipt)r).outcome()==outcome);
        flags.put("observed",r->((Displacement.Receipt)r).change().isPresent());flags.put("available",flags.get("observed"));flags.put("missing",r->((Displacement.Receipt)r).change().isEmpty());
        flags.put("changed",r->((Displacement.Receipt)r).outcome()==Displacement.Outcome.APPLIED);flags.put("clipped",r->((Displacement.Receipt)r).clipped());
        RESULT=new ResultShape(fields,flags,false,ResultShape.Reference.POSITION);
    }
    private static Displacement.Offset vector(Displacement.Receipt r,String phase){var c=r.requireChange();return switch(phase){
        case "before"->offset(c.before());case "after"->offset(c.after());case "requested"->r.command().requested().orElseThrow();case "resolved"->c.resolved();case "delta"->c.delta();default->throw new AssertionError(phase);};}
    private static Displacement.Offset offset(WorldPosition p){return new Displacement.Offset(p.x(),p.y(),p.z());}
    private static double component(Displacement.Offset v,String axis){return switch(axis){case "x"->v.x();case "y"->v.y();case "z"->v.z();default->throw new AssertionError(axis);};}
    private static double read(Value value,Unit unit,Evaluation e){var result=value.evaluate(e);Validation.same(result.unit(),unit);return result.value();}
    /** An explicit world axis tied to a captured position's dimension, independent of actor pitch/yaw. */
    public record Direction(String position,Value x,Value y,Value z) implements Action {
        @Override public ResultShape validate(Validation v){v.result(position).requirePosition();for(var value:List.of(x,y,z))Validation.same(value.unit(v),Unit.MULTIPLIER);return ResultShape.DIRECTION;}
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e){
            var point=e.results().get(position).position(e.context().bindings().get(position));double dx=read(x,Unit.MULTIPLIER,e),dy=read(y,Unit.MULTIPLIER,e),dz=read(z,Unit.MULTIPLIER,e);
            var direction=point.filter(_->dx!=0||dy!=0||dz!=0).map(p->new WorldDirection(p.dimension(),dx,dy,dz));
            return new RuleEngine.Local<>(e.state(),new DirectionResult.Stored(direction),List.of());
        }
    }
    public record Apply(Evaluation.Target target,String direction,Value distance,ActionOrigin origin,Set<String> tags) implements Action {
        public Apply{Objects.requireNonNull(target);Objects.requireNonNull(direction);Objects.requireNonNull(distance);Objects.requireNonNull(origin);tags=Set.copyOf(tags);}
        @Override public ResultShape validate(Validation v){v.target(target);v.result(direction).requireDirection();Validation.same(distance.unit(v),Unit.METER);if(distance instanceof Value.Constant c)Numbers.nonnegative(c.value(),"displacement distance");return RESULT;}
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e){
            var axis=e.results().get(direction).direction(e.context().bindings().get(direction));return new RuleEngine.Await<>(new Displacement.Command(e.target(target),axis,read(distance,Unit.METER,e),origin.resolve(e),tags));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e,RuleEngine.ActionResult result){
            var receipt=(Displacement.Receipt)result;var command=e.context().command(Displacement.Command.class);if(!receipt.command().equals(command))throw new IllegalArgumentException("Displacement receipt differs from issued command");
            if(receipt.outcome()!=Displacement.Outcome.APPLIED)return new RuleEngine.Local<>(e.state(),receipt,List.of());
            var numbers=new HashMap<String,Measure>();RESULT.fields().forEach((name,field)->numbers.put(name,new Measure(field.read().applyAsDouble(receipt),field.unit())));
            var tags=new HashSet<>(command.origin().tags());tags.addAll(command.tags());tags.add("chorus:displacement");
            var event=new EffectEvent(command.origin().owner(),command.target(),command.origin(),tags,numbers,Map.of("clipped",receipt.clipped()),Map.of("dimension",receipt.requireChange().after().dimension()));
            return new RuleEngine.Local<>(e.state(),receipt,List.of(new RuleEngine.Signal("chorus:entity_displaced",new Displacement.Applied(event,receipt))));
        }
    }
}
