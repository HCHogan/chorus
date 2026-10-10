package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.buff.BuffDefinition;
import com.imdomestic.chorus.stat.*;
import java.util.List;
import java.util.Optional;

/** Immutable, decoded ruleset. CompiledEffects validates references and types before execution. */
public record EffectProgram(String version, List<Buff> buffs, List<Bundle> bundles, List<CalculationProfile> profiles,
        Optional<String> defenseProfile, List<com.imdomestic.chorus.effect.resource.ResourceDefinition> resources, com.imdomestic.chorus.effect.equipment.EquipmentSchema equipment, List<com.imdomestic.chorus.effect.ability.AbilityDefinition> abilities, List<com.imdomestic.chorus.effect.weapon.WeaponDefinition> weapons,
        List<com.imdomestic.chorus.effect.attribute.NativeAttributeBinding> nativeAttributes) {
    public EffectProgram {
        buffs = List.copyOf(buffs); bundles = List.copyOf(bundles); profiles = List.copyOf(profiles);
        java.util.Objects.requireNonNull(defenseProfile);
        abilities = List.copyOf(abilities); weapons = List.copyOf(weapons);
        resources = List.copyOf(resources); java.util.Objects.requireNonNull(equipment);
        nativeAttributes = List.copyOf(nativeAttributes);
    }
    public EffectProgram(String version, List<Buff> buffs, List<Bundle> bundles, List<CalculationProfile> profiles, Optional<String> defenseProfile, List<com.imdomestic.chorus.effect.resource.ResourceDefinition> resources, com.imdomestic.chorus.effect.equipment.EquipmentSchema equipment, List<com.imdomestic.chorus.effect.ability.AbilityDefinition> abilities, List<com.imdomestic.chorus.effect.weapon.WeaponDefinition> weapons) {
        this(version,buffs,bundles,profiles,defenseProfile,resources,equipment,abilities,weapons,List.of());
    }
    public EffectProgram(String version, List<Buff> buffs, List<Bundle> bundles, List<CalculationProfile> profiles, Optional<String> defenseProfile, List<com.imdomestic.chorus.effect.resource.ResourceDefinition> resources, com.imdomestic.chorus.effect.equipment.EquipmentSchema equipment, List<com.imdomestic.chorus.effect.ability.AbilityDefinition> abilities) {
        this(version, buffs, bundles, profiles, defenseProfile, resources, equipment, abilities, List.of());
    }
    public EffectProgram(String version, List<Buff> buffs, List<Bundle> bundles, List<CalculationProfile> profiles, Optional<String> defenseProfile, List<com.imdomestic.chorus.effect.resource.ResourceDefinition> resources, com.imdomestic.chorus.effect.equipment.EquipmentSchema equipment) {
        this(version, buffs, bundles, profiles, defenseProfile, resources, equipment, List.of());
    }
    public EffectProgram(String version, List<Buff> buffs, List<Bundle> bundles, List<CalculationProfile> profiles, Optional<String> defenseProfile, List<com.imdomestic.chorus.effect.resource.ResourceDefinition> resources) {
        this(version, buffs, bundles, profiles, defenseProfile, resources, com.imdomestic.chorus.effect.equipment.EquipmentSchema.EMPTY);
    }
    public EffectProgram(String version, List<Buff> buffs, List<Bundle> bundles, List<CalculationProfile> profiles, Optional<String> defenseProfile) {
        this(version, buffs, bundles, profiles, defenseProfile, List.of());
    }
    public EffectProgram(String version, List<Buff> buffs, List<Bundle> bundles, List<CalculationProfile> profiles) {
        this(version, buffs, bundles, profiles, Optional.empty());
    }
    public enum Scope { SOURCE, BUFF }
    public enum Multiplicity { INSTANCE, STACK }
    public enum Evaluate { ON_USE, ON_HIT }
    /** Which query participant owns the source or Buff providing this contribution. */
    public enum ModifierProvider { HOLDER, VICTIM }
    public enum ReactionBinding { CURRENT_OWNER_BUNDLE, ORIGIN_BUNDLE }
    public record Shield(String capacity, Value takenMultiplier, int priority, Optional<Value> maximum, Optional<ShieldRecovery> recovery, java.util.Set<String> excludedAttackFactors) {
        public Shield {
            java.util.Objects.requireNonNull(maximum); java.util.Objects.requireNonNull(recovery); excludedAttackFactors = java.util.Set.copyOf(excludedAttackFactors);
            for (var factor : excludedAttackFactors) if (!factor.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid excluded attack factor: " + factor);
        }
        public Shield(String capacity, Value takenMultiplier, int priority, Optional<Value> maximum, Optional<ShieldRecovery> recovery) { this(capacity, takenMultiplier, priority, maximum, recovery, java.util.Set.of()); }
        public Shield(String capacity, Value takenMultiplier, int priority, Optional<Value> maximum) { this(capacity, takenMultiplier, priority, maximum, Optional.empty()); }
        public Shield(String capacity, Value takenMultiplier, int priority) { this(capacity, takenMultiplier, priority, Optional.empty()); }
    }
    public record ShieldRecovery(Value rate, Condition condition) {}
    public record Buff(BuffDefinition definition, String bundle, Optional<Shield> shield, Optional<com.imdomestic.chorus.effect.combat.BuffConsumption.Policy> consumeOnDamage) {
        public Buff { java.util.Objects.requireNonNull(consumeOnDamage); }
        public Buff(BuffDefinition definition, String bundle, Optional<Shield> shield) { this(definition, bundle, shield, Optional.empty()); }
        public Buff(BuffDefinition definition, String bundle) { this(definition, bundle, Optional.empty()); }
    }
    public record Bundle(String id, Scope scope, List<Rule> rules, List<Modifier> modifiers, List<HealthRecovery> recovery, List<com.imdomestic.chorus.effect.ability.AbilityDefinition.Replacement> abilityOverrides,
            java.util.Map<String, com.imdomestic.chorus.stat.Unit> parameters, List<com.imdomestic.chorus.effect.input.ActionGate.Declaration> actionGates,
            List<com.imdomestic.chorus.effect.motion.HorizontalSpeedLimit.Declaration> horizontalSpeedLimits, List<String> includes) {
        public Bundle {
            abilityOverrides = List.copyOf(abilityOverrides); rules = List.copyOf(rules); modifiers = List.copyOf(modifiers); recovery = List.copyOf(recovery);
            parameters = com.imdomestic.chorus.effect.EffectParameters.copy(parameters);
            actionGates = List.copyOf(actionGates); horizontalSpeedLimits = List.copyOf(horizontalSpeedLimits); includes = List.copyOf(includes);
            if (scope != Scope.SOURCE && !parameters.isEmpty()) throw new IllegalArgumentException("Only source bundles declare parameters");
        }
        public Bundle(String id, Scope scope, List<Rule> rules, List<Modifier> modifiers, List<HealthRecovery> recovery, List<com.imdomestic.chorus.effect.ability.AbilityDefinition.Replacement> abilityOverrides,
                java.util.Map<String, com.imdomestic.chorus.stat.Unit> parameters, List<com.imdomestic.chorus.effect.input.ActionGate.Declaration> actionGates,
                List<com.imdomestic.chorus.effect.motion.HorizontalSpeedLimit.Declaration> horizontalSpeedLimits) { this(id, scope, rules, modifiers, recovery, abilityOverrides, parameters, actionGates, horizontalSpeedLimits, List.of()); }
        public Bundle(String id, Scope scope, List<Rule> rules, List<Modifier> modifiers, List<HealthRecovery> recovery, List<com.imdomestic.chorus.effect.ability.AbilityDefinition.Replacement> abilityOverrides,
                java.util.Map<String, com.imdomestic.chorus.stat.Unit> parameters, List<com.imdomestic.chorus.effect.input.ActionGate.Declaration> actionGates) { this(id, scope, rules, modifiers, recovery, abilityOverrides, parameters, actionGates, List.of()); }
        public Bundle(String id, Scope scope, List<Rule> rules, List<Modifier> modifiers, List<HealthRecovery> recovery, List<com.imdomestic.chorus.effect.ability.AbilityDefinition.Replacement> abilityOverrides,
                java.util.Map<String, com.imdomestic.chorus.stat.Unit> parameters) { this(id, scope, rules, modifiers, recovery, abilityOverrides, parameters, List.of()); }
        public Bundle(String id, Scope scope, List<Rule> rules, List<Modifier> modifiers, List<HealthRecovery> recovery, List<com.imdomestic.chorus.effect.ability.AbilityDefinition.Replacement> abilityOverrides) { this(id, scope, rules, modifiers, recovery, abilityOverrides, java.util.Map.of()); }
        public Bundle(String id, Scope scope, List<Rule> rules, List<Modifier> modifiers, List<HealthRecovery> recovery) { this(id, scope, rules, modifiers, recovery, List.of()); }
        public Bundle(String id, Scope scope, List<Rule> rules, List<Modifier> modifiers) { this(id, scope, rules, modifiers, List.of()); }
    }
    public record HealthRecovery(String id, String channel, Value rate, int priority, Condition condition, java.util.Set<String> tags, Optional<String> profile) {
        public HealthRecovery { tags = java.util.Set.copyOf(tags); java.util.Objects.requireNonNull(profile); }
        public HealthRecovery(String id, String channel, Value rate, int priority, Condition condition, java.util.Set<String> tags) {
            this(id, channel, rate, priority, condition, tags, Optional.empty());
        }
    }
    public record Rule(String id, String on, Condition condition, List<Step> actions, ReactionBinding binding, Optional<String> procKey) {
        public Rule { actions = List.copyOf(actions); java.util.Objects.requireNonNull(binding); java.util.Objects.requireNonNull(procKey); }
        public Rule(String id, String on, Condition condition, List<Step> actions, ReactionBinding binding) { this(id, on, condition, actions, binding, Optional.empty()); }
        public Rule(String id, String on, Condition condition, List<Step> actions) { this(id, on, condition, actions, ReactionBinding.CURRENT_OWNER_BUNDLE); }
    }
    public sealed interface Step permits Instruction, Branch, ForEach, After, Projectile, Pickup {}
    public record Pickup(PickupSpec spec, String bind, List<Step> body, Optional<String> spawnBind) implements Step {
        public Pickup(PickupSpec spec, String bind, List<Step> body) { this(spec, bind, body, Optional.empty()); }
        public Pickup { java.util.Objects.requireNonNull(spec); java.util.Objects.requireNonNull(bind); body = List.copyOf(body); java.util.Objects.requireNonNull(spawnBind); }
    }
    public record Projectile(ProjectileSpec spec, String bind, List<Step> body, Optional<ShotActions.Membership> shot) implements Step {
        public Projectile(ProjectileSpec spec, String bind, List<Step> body) { this(spec, bind, body, Optional.empty()); }
        public Projectile { java.util.Objects.requireNonNull(shot); java.util.Objects.requireNonNull(spec); java.util.Objects.requireNonNull(bind); body = List.copyOf(body); }
    }
    public record After(Value delay, com.imdomestic.chorus.effect.EffectContinuations.Lifetime lifetime, List<Step> body) implements Step {
        public After { java.util.Objects.requireNonNull(delay); java.util.Objects.requireNonNull(lifetime); body = List.copyOf(body); }
    }
    public record Instruction(Action action, String bind) implements Step {}
    public record Branch(Condition condition, List<Step> then, List<Step> otherwise) implements Step {
        public Branch { then = List.copyOf(then); otherwise = List.copyOf(otherwise); }
    }
    public record ForEach(String collection, String bind, List<Step> body) implements Step {
        public ForEach { body = List.copyOf(body); }
    }
    public record Modifier(String id, String profile, String stage, String group, NumericContribution.Operation operation,
            Value value, String percentOf, String stackingKey, int priority, Condition condition,
            String reference, NumericContribution.Confidence confidence, Multiplicity multiplicity, Evaluate evaluate, ModifierProvider provider) {
        public Modifier { java.util.Objects.requireNonNull(evaluate); java.util.Objects.requireNonNull(provider); }
        public Modifier(String id, String profile, String stage, String group, NumericContribution.Operation operation,
                Value value, String percentOf, String stackingKey, int priority, Condition condition,
                String reference, NumericContribution.Confidence confidence, Multiplicity multiplicity, Evaluate evaluate) {
            this(id, profile, stage, group, operation, value, percentOf, stackingKey, priority, condition, reference, confidence, multiplicity, evaluate, ModifierProvider.HOLDER);
        }
        public Modifier(String id, String profile, String stage, String group, NumericContribution.Operation operation,
                Value value, String percentOf, String stackingKey, int priority, Condition condition,
                String reference, NumericContribution.Confidence confidence, Multiplicity multiplicity) {
            this(id, profile, stage, group, operation, value, percentOf, stackingKey, priority, condition, reference, confidence, multiplicity, Evaluate.ON_USE);
        }
    }
}
