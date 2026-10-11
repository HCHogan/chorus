package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Compile contribution routing once; queries still visit each actual modifier exactly once. */
final class SharedStages {
    private record Key(String profile, String stage) {}
    private record Stage(CalculationStep step, Unit unit) {}
    private final Map<Key, Stage> stages = new HashMap<>();
    private final Map<Key, Set<String>> providers = new HashMap<>();

    SharedStages(Map<String, CalculationProfile> profiles) {
        for (var profile : profiles.values()) {
            Unit unit = profile.inputUnit();
            for (var step : profile.steps()) {
                var local = CalculationStep.local(step);
                if (local instanceof CalculationStep.Apply apply) {
                    Unit contributionUnit = switch (apply.operation()) {
                        case ADD, REPLACE -> unit;
                        case MULTIPLY, BASE_PERCENT -> Unit.DELTA;
                        case RESIST -> Unit.RESISTANCE;
                    };
                    stages.put(new Key(profile.id(), step.id()), new Stage(step, contributionUnit));
                }
                if (local instanceof CalculationStep.Transform transform) unit = transform.outputUnit();
            }
        }
        for (var key : stages.keySet()) resolve(key, new HashSet<>());
    }

    private Set<String> resolve(Key key, Set<Key> path) {
        if (providers.containsKey(key)) return providers.get(key);
        if (!path.add(key)) throw new IllegalArgumentException("Circular shared numeric stage: " + key);
        var stage = stages.get(key);
        if (stage == null) throw new IllegalArgumentException("Missing shared numeric stage: " + key);
        var result = new HashSet<String>(); result.add(key.profile());
        if (stage.step() instanceof CalculationStep.Shared shared) {
            var parent = new Key(shared.profile(), key.stage());
            var definition = stages.get(parent);
            if (definition == null) throw new IllegalArgumentException("Missing shared numeric stage: " + parent);
            if (!shared.apply().equals(CalculationStep.local(definition.step())) || !stage.unit().equals(definition.unit()))
                throw new IllegalArgumentException("Shared numeric stage layout or contribution unit differs: " + key);
            result.addAll(resolve(parent, path));
        }
        path.remove(key);
        var immutable = Set.copyOf(result); providers.put(key, immutable); return immutable;
    }

    boolean accepts(String destination, String source, String stage) {
        return destination.equals(source) || providers.getOrDefault(new Key(destination, stage), Set.of()).contains(source);
    }
}
