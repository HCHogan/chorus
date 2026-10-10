package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.ability.AbilityDefinition;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.motion.HorizontalSpeedLimit;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;

/** Static declaration composition. This graph does not constrain runtime event feedback. */
final class BundleComposition {
    private BundleComposition() {}
    static List<EffectProgram.Bundle> resolve(List<EffectProgram.Bundle> declarations) {
        var catalogue=new LinkedHashMap<String,EffectProgram.Bundle>();
        for(var bundle:declarations)if(catalogue.putIfAbsent(bundle.id(),bundle)!=null)throw new IllegalArgumentException("Duplicate definition: "+bundle.id());
        var result=new ArrayList<EffectProgram.Bundle>();
        for(var root:declarations){
            var ordered=new LinkedHashMap<String,EffectProgram.Bundle>();
            visit(root,root.scope(),catalogue,new LinkedHashSet<>(),ordered);
            var rules=new ArrayList<EffectProgram.Rule>();var modifiers=new ArrayList<EffectProgram.Modifier>();var recovery=new ArrayList<EffectProgram.HealthRecovery>();
            var overrides=new ArrayList<AbilityDefinition.Replacement>();var gates=new ArrayList<ActionGate.Declaration>();var limits=new ArrayList<HorizontalSpeedLimit.Declaration>();var parameters=new LinkedHashMap<String,Unit>();
            for(var part:ordered.values()){
                rules.addAll(part.rules());modifiers.addAll(part.modifiers());recovery.addAll(part.recovery());overrides.addAll(part.abilityOverrides());gates.addAll(part.actionGates());limits.addAll(part.horizontalSpeedLimits());
                part.parameters().forEach((name,unit)->{var old=parameters.putIfAbsent(name,unit);if(old!=null&&!old.equals(unit))throw new IllegalArgumentException("Conflicting included parameter "+root.id()+"/"+name);});
            }
            // Existing compilation validates every declaration category and rejects local-ID collisions.
            result.add(new EffectProgram.Bundle(root.id(),root.scope(),rules,modifiers,recovery,overrides,parameters,gates,limits));
        }
        return List.copyOf(result);
    }
    private static void visit(EffectProgram.Bundle bundle,EffectProgram.Scope scope,Map<String,EffectProgram.Bundle> catalogue,Set<String> active,Map<String,EffectProgram.Bundle> ordered){
        if(bundle.scope()!=scope)throw new IllegalArgumentException("Included bundle scope differs: "+bundle.id());
        if(active.contains(bundle.id()))throw new IllegalArgumentException("Cyclic bundle includes: "+active+" -> "+bundle.id());
        if(ordered.containsKey(bundle.id()))return;
        active.add(bundle.id());var direct=new HashSet<String>();
        for(var id:bundle.includes()){
            if(!direct.add(id))throw new IllegalArgumentException("Duplicate bundle include: "+bundle.id()+" -> "+id);
            var dependency=catalogue.get(id);if(dependency==null)throw new IllegalArgumentException("Missing included bundle: "+id);
            visit(dependency,scope,catalogue,active,ordered);
        }
        active.remove(bundle.id());ordered.put(bundle.id(),bundle);
    }
}
