package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import java.util.*;

public final class BundleActions {
    private BundleActions() {}
    public record Invoke(String bundle, String event, Evaluation.Target holder, Evaluation.Target target,
            ActionOrigin origin, Map<String, Value> parameters, Set<String> tags, Map<String, Value> numbers) implements Action {
        public Invoke {
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(bundle);
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(event);
            if (event.startsWith("chorus:internal/")) throw new IllegalArgumentException("Reserved internal event");
            Objects.requireNonNull(holder); Objects.requireNonNull(target); Objects.requireNonNull(origin);
            parameters=EffectParameters.copy(parameters); tags=Set.copyOf(tags); numbers=Map.copyOf(numbers);
            if (numbers.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Blank invocation measurement");
        }
        @Override public ResultShape validate(Validation v) {
            v.target(holder); v.target(target);
            var definition=v.bundles().get(bundle);
            if (definition==null || definition.scope()!=EffectProgram.Scope.SOURCE) throw new IllegalArgumentException("Invocation requires a source bundle: "+bundle);
            if (definition.rules().stream().noneMatch(rule->rule.on().equals(event))) throw new IllegalArgumentException("Bundle has no rule for invocation event: "+event);
            if (!definition.parameters().keySet().equals(parameters.keySet())) throw new IllegalArgumentException("Invocation parameters do not match bundle: "+bundle);
            parameters.forEach((name,value)->Validation.same(value.unit(v),definition.parameters().get(name)));
            numbers.values().forEach(value->value.unit(v));
            return ResultShape.EMPTY;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var program=e.program().orElseThrow(()->new IllegalStateException("Invocation requires a compiled program"));
            var context=e.timerEvent(); var attribution=origin.resolve(e); var op=e.context().operation();
            String instance="invoke/"+op.frame()+"/"+op.pc()+"/"+op.invocation();
            var values=new TreeMap<String,Measure>(); parameters.forEach((name,value)->values.put(name,value.evaluate(e)));
            var source=new EffectSource(instance,bundle,e.target(holder),attribution,Set.of(),values);program.validateSource(source);
            var measurements=new HashMap<>(context.numbers());numbers.forEach((name,value)->measurements.put(name,value.evaluate(e)));
            var references=new HashMap<>(context.references());references.put("source_instance",instance);references.put("bundle",bundle);
            var query=new EffectEvent(context.actor(),e.target(target),attribution,tags,measurements,context.flags(),references,context.impact(),
                    attribution.owner().equals(context.source().owner())?context.reactions():Optional.empty(),context.proc(),context.observedBuffs(),context.observedEntities());
            return new RuleEngine.Local<>(e.state(),RuleEngine.Empty.INSTANCE,List.of(new RuleEngine.Signal(event,new BundleInvocation(program.program().version(),source,query))));
        }
    }
}
