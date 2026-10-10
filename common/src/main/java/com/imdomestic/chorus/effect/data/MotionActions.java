package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.function.Predicate;

public final class MotionActions {
    private MotionActions() {}
    public static final ResultShape RESULT;
    static {
        var fields=new HashMap<String,ResultShape.Field>();
        for(String phase:List.of("before","after","delta"))for(String axis:List.of("x","y","z"))
            fields.put(phase+"_"+axis,new ResultShape.Field(Impulse.SPEED,r->component(velocity(((Impulse.Receipt)r).requireChange(),phase),axis)));
        var flags=new HashMap<String,Predicate<RuleEngine.ActionResult>>();
        for(var outcome:Impulse.Outcome.values())flags.put(outcome.name().toLowerCase(Locale.ROOT),r->((Impulse.Receipt)r).outcome()==outcome);
        flags.put("observed",r->((Impulse.Receipt)r).change().isPresent());
        flags.put("changed",r->((Impulse.Receipt)r).outcome()==Impulse.Outcome.APPLIED);
        RESULT=new ResultShape(fields,flags);
    }
    private static Impulse.Velocity velocity(Impulse.Change c,String phase) { return switch(phase) {case "before"->c.before();case "after"->c.after();case "delta"->c.delta();default->throw new AssertionError(phase);}; }
    private static double component(Impulse.Velocity v,String axis) { return switch(axis) {case "x"->v.x();case "y"->v.y();case "z"->v.z();default->throw new AssertionError(axis);}; }
    public record AxisScale(Value x,Value y,Value z) {
        public static final AxisScale ONE=new AxisScale(new Value.Constant(1,Unit.MULTIPLIER),new Value.Constant(1,Unit.MULTIPLIER),new Value.Constant(1,Unit.MULTIPLIER));
        public AxisScale {Objects.requireNonNull(x);Objects.requireNonNull(y);Objects.requireNonNull(z);}
        void validate(Validation v){for(var value:List.of(x,y,z))Validation.same(value.unit(v),Unit.MULTIPLIER);}
        Impulse.Scale resolve(Evaluation e){return new Impulse.Scale(read(x,Unit.MULTIPLIER,e),read(y,Unit.MULTIPLIER,e),read(z,Unit.MULTIPLIER,e));}
    }
    private static double read(Value value,Unit unit,Evaluation e){var result=value.evaluate(e);Validation.same(result.unit(),unit);return result.value();}
    public record Apply(Evaluation.Target target,String direction,Value speed,AxisScale axisScale,ActionOrigin origin,Set<String> tags) implements Action {
        public Apply {Objects.requireNonNull(target);Objects.requireNonNull(direction);Objects.requireNonNull(speed);Objects.requireNonNull(axisScale);Objects.requireNonNull(origin);tags=Set.copyOf(tags);}
        @Override public ResultShape validate(Validation v){v.target(target);v.result(direction).requireDirection();Validation.same(speed.unit(v),Impulse.SPEED);axisScale.validate(v);
            if(speed instanceof Value.Constant c)Numbers.nonnegative(c.value(),"impulse speed");return RESULT;}
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e){
            var axis=e.results().get(direction).direction(e.context().bindings().get(direction));
            return new RuleEngine.Await<>(new Impulse.Command(e.target(target),axis,read(speed,Impulse.SPEED,e),axisScale.resolve(e),origin.resolve(e),tags));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e,RuleEngine.ActionResult result){
            var receipt=(Impulse.Receipt)result;var command=e.context().command(Impulse.Command.class);
            if(!receipt.command().equals(command))throw new IllegalArgumentException("Impulse receipt differs from issued command");
            if(receipt.outcome()!=Impulse.Outcome.APPLIED)return new RuleEngine.Local<>(e.state(),receipt,List.of());
            var numbers=new HashMap<String,Measure>();RESULT.fields().forEach((name,field)->numbers.put(name,new Measure(field.read().applyAsDouble(receipt),field.unit())));
            var eventTags=new HashSet<>(command.origin().tags());eventTags.addAll(command.tags());eventTags.add("chorus:impulse");
            var event=new EffectEvent(command.origin().owner(),command.target(),command.origin(),eventTags,numbers);
            return new RuleEngine.Local<>(e.state(),receipt,List.of(new RuleEngine.Signal("chorus:impulse_applied",new Impulse.Applied(event,receipt))));
        }
    }
    public record Between(String from,String to) implements Action {
        public Between {Objects.requireNonNull(from);Objects.requireNonNull(to);}
        @Override public ResultShape validate(Validation v){v.result(from).requirePosition();v.result(to).requirePosition();return ResultShape.DIRECTION;}
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e){
            var start=e.results().get(from).position(e.context().bindings().get(from));var end=e.results().get(to).position(e.context().bindings().get(to));
            Optional<WorldDirection> result=Optional.empty();
            if(start.isPresent()&&end.isPresent()&&start.get().dimension().equals(end.get().dimension())){
                var a=start.get();var b=end.get();double x=b.x()-a.x(),y=b.y()-a.y(),z=b.z()-a.z();
                if(x!=0||y!=0||z!=0)result=Optional.of(new WorldDirection(a.dimension(),x,y,z));
            }
            return new RuleEngine.Local<>(e.state(),new DirectionResult.Stored(result),List.of());
        }
    }
}
