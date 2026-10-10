package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Map;

public record Evaluation(EffectState state, RuleEngine.Context context, Map<String, BuffDefinition> buffs,
        Map<String, ResultShape> results, Map<String, ResourceDefinition> resources, Map<String, RuleEngine.ActionResult> retainedResults,
        java.util.Optional<CompiledEffects> program) {
    public Evaluation { retainedResults = Map.copyOf(retainedResults); java.util.Objects.requireNonNull(program); }
    public Evaluation(EffectState state, RuleEngine.Context context, Map<String, BuffDefinition> buffs, Map<String, ResultShape> results,
            Map<String, ResourceDefinition> resources, Map<String, RuleEngine.ActionResult> retainedResults) {
        this(state, context, buffs, results, resources, retainedResults, java.util.Optional.empty());
    }
    public Evaluation(EffectState state, RuleEngine.Context context, Map<String, BuffDefinition> buffs, Map<String, ResultShape> results,
            Map<String, ResourceDefinition> resources) {
        this(state, context, buffs, results, resources, context.retainedResults());
    }
    public Evaluation(EffectState state, RuleEngine.Context context, Map<String, BuffDefinition> buffs, Map<String, ResultShape> results) {
        this(state, context, buffs, results, Map.of());
    }
    public sealed interface Center permits Target, BoundPosition {}
    public record BoundPosition(String binding) implements Center {
        public BoundPosition { EffectTimers.localName(binding); }
    }
    public sealed interface Target extends Center permits BuiltinTarget, BoundTarget {
        Target SELF = BuiltinTarget.SELF, VICTIM = BuiltinTarget.VICTIM, EVENT_ACTOR = BuiltinTarget.EVENT_ACTOR,
                SOURCE_OWNER = BuiltinTarget.SOURCE_OWNER, THIS_WEAPON = BuiltinTarget.THIS_WEAPON, EVENT_WEAPON = BuiltinTarget.EVENT_WEAPON;
    }
    public enum BuiltinTarget implements Target { SELF, VICTIM, EVENT_ACTOR, SOURCE_OWNER, THIS_WEAPON, EVENT_WEAPON }
    public record BoundTarget(String binding) implements Target {
        public BoundTarget { EffectTimers.localName(binding); }
    }
    public com.imdomestic.chorus.effect.target.TargetQuery.Center center(Center center) {
        return switch (center) {
            case Target target -> new com.imdomestic.chorus.effect.target.TargetQuery.EntityCenter(target(target));
            case BoundPosition bound -> {
                var shape = results.get(bound.binding()); var result = context.bindings().get(bound.binding());
                if (shape == null || result == null) throw new IllegalArgumentException("Unbound position: " + bound.binding());
                yield new com.imdomestic.chorus.effect.target.TargetQuery.PositionCenter(shape.position(result));
            }
        };
    }
    public BuffInstance.Origin origin() {
        if (context.scope() instanceof com.imdomestic.chorus.effect.weapon.WeaponFire.Scope scope) return scope.event().source();
        if (context.scope() instanceof com.imdomestic.chorus.effect.weapon.WeaponReload.Scope scope) return scope.event().source();
        if (context.scope() instanceof com.imdomestic.chorus.effect.ability.AbilityUse.Scope scope) return scope.event().source();
        if (context.scope() instanceof EffectSource source) {
            var tags = new java.util.HashSet<>(source.origin().tags()); tags.addAll(source.tags());
            return new BuffInstance.Origin(source.origin().owner(), source.origin().source(), source.origin().weapon(), source.origin().ability(), tags);
        }
        if (context.scope() instanceof BuffRules.Scope scope) return scope.snapshot().origin();
        throw new IllegalStateException("Rule has no bound effect source");
    }
    public String self() {
        if (context.scope() instanceof com.imdomestic.chorus.effect.weapon.WeaponFire.Scope scope) return scope.event().actor();
        if (context.scope() instanceof com.imdomestic.chorus.effect.weapon.WeaponReload.Scope scope) return scope.event().actor();
        if (context.scope() instanceof com.imdomestic.chorus.effect.ability.AbilityUse.Scope scope) return scope.event().actor();
        if (context.scope() instanceof EffectSource source) return source.holder();
        if (context.scope() instanceof BuffRules.Scope scope) return scope.snapshot().key().holder();
        throw new IllegalStateException("Rule has no bound holder");
    }
    public String target(Target target) {
        String value = targetValue(target);
        if (value.isBlank()) throw new IllegalArgumentException("Missing target reference: " + target);
        return value;
    }
    /** Reference availability only: never looks up an entity or hides invalid lexical bindings. */
    public java.util.Optional<String> targetReference(Target target) {
        if ((target == Target.VICTIM || target == Target.EVENT_ACTOR || target == Target.EVENT_WEAPON)
                && EffectTimers.event(context.event().signal().payload()).isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(targetValue(target)).filter(value -> !value.isBlank());
    }
    private String targetValue(Target target) {
        String value = switch (target) {
            case BuiltinTarget builtin -> switch (builtin) {
                case SELF -> self(); case VICTIM -> event().victim(); case EVENT_ACTOR -> event().actor();
                case SOURCE_OWNER -> origin().owner(); case THIS_WEAPON -> origin().weapon(); case EVENT_WEAPON -> event().source().weapon();
            };
            case BoundTarget bound -> {
                var shape = results.get(bound.binding()); var result = context.bindings().get(bound.binding());
                if (shape == null || result == null) throw new IllegalArgumentException("Unbound target: " + bound.binding());
                yield shape.target(result);
            }
        };
        return value;
    }
    public EffectEvent event() {
        return EffectTimers.event(context.event().signal().payload()).orElseThrow(() -> new IllegalArgumentException("Event does not contain combat/input facts"));
    }
    public EffectEvent timerEvent() {
        return EffectTimers.event(context.event().signal().payload()).orElseGet(() -> {
            if (context.event().signal().payload() instanceof Buffs.Change change) {
                var instance = change.instance();
                return new EffectEvent(instance.origin().owner(), instance.key().holder(), instance.origin(), instance.definition().tags(), Map.of());
            }
            throw new IllegalArgumentException("Cannot schedule an event without explicit context");
        });
    }
    public BuffDefinition definition(String id) {
        var definition = buffs.get(id);
        if (definition == null) throw new IllegalArgumentException("Unknown buff: " + id);
        return definition;
    }
    public ResourceDefinition resourceDefinition(String id) {
        var definition = resources.get(id); if (definition == null) throw new IllegalArgumentException("Unknown resource: " + id); return definition;
    }
    public ResourceState resource(String id, Target target) {
        var account = state.resources().get(new ResourceState.Key(target(target), id));
        if (account == null) throw new IllegalArgumentException("Missing resource account: " + id);
        resourceDefinition(id).validate(account); return account;
    }
    public com.imdomestic.chorus.effect.ammo.AmmoState ammo(Target weapon) {
        var state = this.state.ammunition().get(target(weapon));
        if (state == null) throw new IllegalArgumentException("Missing ammunition account");
        return state;
    }
    public com.imdomestic.chorus.effect.ammo.AmmoCapacity.View ammoView(EffectState state, com.imdomestic.chorus.effect.ammo.AmmoState account) {
        return account.capacityProfile().isEmpty() ? com.imdomestic.chorus.effect.ammo.AmmoCapacity.View.fixed(account)
                : program.orElseThrow(() -> new IllegalArgumentException("Dynamic ammunition requires a compiled program"))
                        .ammoCapacity(state, account, NumericQuery.Path.from(context.event().signal().payload()));
    }
    public com.imdomestic.chorus.effect.ammo.AmmoCapacity.View ammoView(Target weapon) { return ammoView(state, ammo(weapon)); }
    /** Lexical visibility applies to the reference; refund accounting includes all executed branches. */
    public Resources.CostReceipt cost(String binding) {
        var shape = results.get(binding); var result = context.bindings().get(binding);
        if (shape == null || result == null) throw new IllegalArgumentException("Unbound cost receipt: " + binding);
        return Resources.latestClaim(shape.cost(result), retainedResults.values());
    }
    public BuffInstance.Key key(String id, Target target) {
        String holder = target(target); return Buffs.key(definition(id), holder, holder, origin());
    }
    public BuffInstance ownBuff() {
        if (!(context.scope() instanceof BuffRules.Scope scope)) throw new IllegalArgumentException("This value requires a buff source");
        return scope.current(state.buffs()).orElseThrow(() -> new IllegalArgumentException("Buff source is no longer active"));
    }
    public BuffInstance readBuff(String id, Target target) {
        var key = key(id, target);
        if (context.scope() instanceof BuffRules.Scope scope && (scope.ended() || scope.retained()) && scope.snapshot().key().equals(key)) return scope.snapshot();
        return state.buffs().active(key).orElseThrow(() -> new IllegalArgumentException("Missing buff instance: " + key));
    }
    public boolean enhanced() {
        return origin().tags().contains("chorus:enhanced");
    }
    public DamageSnapshot snapshot(String binding) {
        var shape = results.get(binding); var result = context.bindings().get(binding);
        if (shape == null || result == null) throw new IllegalArgumentException("Unbound snapshot: " + binding);
        return shape.snapshot(result);
    }
}
