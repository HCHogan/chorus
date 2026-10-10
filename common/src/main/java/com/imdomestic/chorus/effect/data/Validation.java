package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.buff.BuffDefinition;
import com.imdomestic.chorus.stat.Unit;
import com.imdomestic.chorus.stat.CalculationProfile;
import com.imdomestic.chorus.effect.resource.ResourceDefinition;
import java.util.Map;

/** Compile-time environment, including only results from preceding instructions. */
public record Validation(Map<String, BuffDefinition> buffs, Map<String, ResultShape> results, boolean buffSource,
        Map<String, CalculationProfile> profiles, Map<String, ResourceDefinition> resources, Map<String, EffectProgram.Shield> shields, Map<String, Unit> parameters) {
    public Validation { buffs = Map.copyOf(buffs); results = Map.copyOf(results); profiles = Map.copyOf(profiles); resources = Map.copyOf(resources); shields = Map.copyOf(shields); parameters = Map.copyOf(parameters); }
    public Validation(Map<String, BuffDefinition> buffs, Map<String, ResultShape> results, boolean buffSource,
            Map<String, CalculationProfile> profiles, Map<String, ResourceDefinition> resources, Map<String, EffectProgram.Shield> shields) { this(buffs, results, buffSource, profiles, resources, shields, Map.of()); }
    public Validation(Map<String, BuffDefinition> buffs, Map<String, ResultShape> results, boolean buffSource,
            Map<String, CalculationProfile> profiles, Map<String, ResourceDefinition> resources) { this(buffs, results, buffSource, profiles, resources, Map.of()); }
    public Validation(Map<String, BuffDefinition> buffs, Map<String, ResultShape> results, boolean buffSource, Map<String, CalculationProfile> profiles) {
        this(buffs, results, buffSource, profiles, Map.of());
    }
    public Validation(Map<String, BuffDefinition> buffs, Map<String, ResultShape> results, boolean buffSource) {
        this(buffs, results, buffSource, Map.of());
    }
    public CalculationProfile damageProfile(String id) {
        var profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("Unknown damage profile: " + id);
        same(profile.inputUnit(), Unit.DAMAGE); same(profile.outputUnit(), Unit.DAMAGE);
        return profile;
    }
    public CalculationProfile ammoProfile(String id) {
        var profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("Unknown ammunition capacity profile: " + id);
        same(profile.inputUnit(), Unit.ROUND); same(profile.outputUnit(), Unit.ROUND); return profile;
    }
    public CalculationProfile attributeProfile(String id) {
        var profile=profiles.get(id);
        if(profile==null)throw new IllegalArgumentException("Unknown attribute profile: "+id);
        same(profile.inputUnit(),Unit.STAT_POINT);same(profile.outputUnit(),Unit.STAT_POINT);return profile;
    }
    public CalculationProfile multiplierProfile(String id) {
        var profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("Unknown multiplier profile: " + id);
        same(profile.inputUnit(), Unit.MULTIPLIER); same(profile.outputUnit(), Unit.MULTIPLIER);
        return profile;
    }
    public BuffDefinition buff(String id) {
        var value = buffs.get(id); if (value == null) throw new IllegalArgumentException("Unknown buff: " + id); return value;
    }
    public ResourceDefinition resource(String id) {
        var definition = resources.get(id); if (definition == null) throw new IllegalArgumentException("Unknown resource: " + id); return definition;
    }
    public EffectProgram.Shield shield(String id) {
        buff(id); var definition = shields.get(id); if (definition == null) throw new IllegalArgumentException("Buff has no shield declaration: " + id); return definition;
    }
    public ResultShape result(String name) {
        var value = results.get(name); if (value == null) throw new IllegalArgumentException("Result is missing or referenced before binding: " + name); return value;
    }
    public void target(Evaluation.Target target) {
        java.util.Objects.requireNonNull(target);
        if (target instanceof Evaluation.BoundTarget bound) result(bound.binding()).requireTarget();
    }
    public void center(Evaluation.Center center) {
        switch (center) {
            case Evaluation.Target target -> target(target);
            case Evaluation.BoundPosition bound -> result(bound.binding()).requirePosition();
        }
    }
    public void requireBuffSource() { if (!buffSource) throw new IllegalArgumentException("Expression requires a buff source"); }
    public static void same(Unit actual, Unit expected) {
        if (!actual.equals(expected)) throw new IllegalArgumentException("Expected " + expected.id() + ", got " + actual.id());
    }
}
