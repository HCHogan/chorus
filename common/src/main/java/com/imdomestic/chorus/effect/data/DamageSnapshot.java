package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffDefinition;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.combat.ImpactData;
import com.imdomestic.chorus.effect.resource.ResourceDefinition;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Immutable attack data. A pinned profile and partially bound expressions survive source removal and catalogue replacement. */
public record DamageSnapshot(long capturedAt, EffectState.Mode mode, DamageCommand attack, CalculationProfile profile,
        Map<String, BuffDefinition> buffs, Map<String, ResourceDefinition> resources, List<Contribution> contributions,
        Optional<ShieldScaling> shieldScaling) implements RuleEngine.ActionResult {
    public DamageSnapshot {
        Objects.requireNonNull(mode); Objects.requireNonNull(attack); Objects.requireNonNull(profile);
        buffs = Map.copyOf(buffs); resources = Map.copyOf(resources); contributions = List.copyOf(contributions);
        Objects.requireNonNull(shieldScaling);
        if (capturedAt < 0 || attack.snapshot().isPresent() || !attack.impact().numbers().isEmpty() || !attack.scalingProfile().equals(Optional.of(profile.id()))) {
            throw new IllegalArgumentException("Invalid damage snapshot input");
        }
        if (!attack.shieldScalingProfile().equals(shieldScaling.map(value -> value.profile().id()))) throw new IllegalArgumentException("Snapshot shield profile differs from attack");
    }
    public DamageSnapshot(long capturedAt, EffectState.Mode mode, DamageCommand attack, CalculationProfile profile,
            Map<String, BuffDefinition> buffs, Map<String, ResourceDefinition> resources, List<Contribution> contributions) {
        this(capturedAt, mode, attack, profile, buffs, resources, contributions, Optional.empty());
    }
    public record ShieldScaling(CalculationProfile profile, List<Contribution> contributions) {
        public ShieldScaling {
            Objects.requireNonNull(profile); contributions = List.copyOf(contributions);
            Validation.same(profile.inputUnit(), Unit.MULTIPLIER); Validation.same(profile.outputUnit(), Unit.MULTIPLIER);
        }
    }
    /** The capture-time target is intentionally discarded; every impact has its own live victim. */
    public DamageCommand command(String target) {
        return command(target, ImpactData.EMPTY);
    }
    public DamageCommand command(String target, ImpactData impact) {
        return new DamageCommand(target, attack.source(), attack.amount(), attack.damageType(), attack.tags(), attack.killTags(),
                attack.nonLethal(), attack.scalingProfile(), Optional.of(this), impact, attack.shieldScalingProfile());
    }
    public void validate(DamageCommand command) {
        if (!attack.source().equals(command.source()) || attack.amount() != command.amount() || !attack.damageType().equals(command.damageType())
                || !attack.tags().equals(command.tags()) || !attack.killTags().equals(command.killTags()) || attack.nonLethal() != command.nonLethal()
                || !attack.scalingProfile().equals(command.scalingProfile()) || !attack.shieldScalingProfile().equals(command.shieldScalingProfile())) throw new IllegalArgumentException("Damage command differs from its captured attack");
    }
    public List<NumericContribution> resolve(EffectState state, RuleEngine.Event event) {
        return resolve(state, event, contributions, Optional.empty());
    }
    public List<NumericContribution> resolve(EffectState state, RuleEngine.Event event, CompiledEffects current) {
        return resolve(state, event, contributions, Optional.of(current));
    }
    public List<NumericContribution> resolveShield(EffectState state, RuleEngine.Event event, CompiledEffects current) {
        return resolve(state, event, shieldScaling.orElseThrow().contributions(), Optional.of(current));
    }
    private List<NumericContribution> resolve(EffectState state, RuleEngine.Event event, List<Contribution> captured, Optional<CompiledEffects> current) {
        if (state.mode() != mode || state.buffs().timeMicros() < capturedAt) throw new IllegalArgumentException("Snapshot impact changed activity mode or precedes capture");
        var result = new ArrayList<NumericContribution>();
        for (var contribution : captured) {
            var e = new Evaluation(state, new RuleEngine.Context(event, contribution.instance(), contribution.scope(), Map.of()), buffs, Map.of(), resources, Map.of(), current);
            if (contribution.condition().test(e)) result.add(contribution.numeric(contribution.value().evaluate(e)));
        }
        return List.copyOf(result);
    }
    /** Scope holds source identities only. Values and conditions have already bound all source-side state reads. */
    public record Contribution(String instance, String source, String definition, String version, EffectSource scope,
            EffectProgram.Modifier modifier, Value value, Condition condition) {
        public Contribution {
            Objects.requireNonNull(instance); Objects.requireNonNull(source); Objects.requireNonNull(definition);
            Objects.requireNonNull(version);
            Objects.requireNonNull(scope); Objects.requireNonNull(modifier); Objects.requireNonNull(value); Objects.requireNonNull(condition);
        }
        public NumericContribution numeric(Measure amount) {
            return new NumericContribution(instance + "/modifier/" + modifier.id(), modifier.stage(), modifier.group(), modifier.operation(), amount,
                    modifier.percentOf(), modifier.stackingKey(), new NumericContribution.Source(definition, source, modifier.reference(), modifier.confidence(), version), modifier.priority());
        }
    }
}
