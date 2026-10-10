package com.imdomestic.chorus.effect.data;

import java.util.*;

/** Link complete roots against one immutable reload snapshot. This graph concerns definitions, not event chains. */
public final class ProgramCatalogue {
    private ProgramCatalogue() {}
    public static Map<String, CompiledEffects> compile(Map<String, ProgramModule> sources) {
        var modules = new TreeMap<>(sources);
        for (var entry : modules.entrySet()) {
            ProgramModule.requireId(entry.getKey()); Objects.requireNonNull(entry.getValue());
            for (var dependency : entry.getValue().imports()) {
                var imported = modules.get(dependency);
                if (imported == null) throw new IllegalArgumentException("Missing module import: " + entry.getKey() + " -> " + dependency);
                if (!imported.program().version().equals(entry.getValue().program().version()))
                    throw new IllegalArgumentException("Mixed module versions: " + entry.getKey() + " -> " + dependency);
            }
        }
        var result = new LinkedHashMap<String, CompiledEffects>();
        for (var entry : modules.entrySet()) {
            if (entry.getValue().fragment()) continue;
            var closure = closure(entry.getKey(), modules);
            try { result.put(entry.getKey(), CompiledEffects.link(closure.stream().map(id -> modules.get(id).program()).toList())); }
            catch (RuntimeException error) { throw new IllegalArgumentException("Invalid effect program " + entry.getKey() + " via " + closure + ": " + error.getMessage(), error); }
        }
        return Collections.unmodifiableMap(result);
    }
    private record Visit(String id, boolean exiting) {}
    private static List<String> closure(String root, Map<String, ProgramModule> modules) {
        var pending = new ArrayDeque<Visit>(); pending.push(new Visit(root, false));
        var discovered = new HashSet<String>(); var ordered = new LinkedHashSet<String>();
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            if (visit.exiting()) { ordered.add(visit.id()); continue; }
            // All definitions are indexed before validation. Diamonds and mutual imports need no repeated expansion.
            if (!discovered.add(visit.id())) continue;
            pending.push(new Visit(visit.id(), true));
            var imports = modules.get(visit.id()).imports();
            for (int i = imports.size() - 1; i >= 0; i--) pending.push(new Visit(imports.get(i), false));
        }
        return List.copyOf(ordered);
    }
}
