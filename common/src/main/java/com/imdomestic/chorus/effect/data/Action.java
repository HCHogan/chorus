package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.EffectTimers;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface Action {
    ResultShape validate(Validation validation);
    RuleEngine.Outcome<EffectState> execute(Evaluation evaluation);
    default RuleEngine.Local<EffectState> complete(Evaluation evaluation, RuleEngine.ActionResult receipt) {
        return new RuleEngine.Local<>(evaluation.state(), receipt, List.of());
    }
    private static RuleEngine.Local<EffectState> local(Evaluation e, Buffs.Result result) {
        return new RuleEngine.Local<>(e.state().withBuffs(result.store()), result.receipt(), result.signals());
    }
    private static void validateCount(Value value, Validation v) {
        Validation.same(value.unit(v), Unit.COUNT);
        if (value instanceof Value.Constant c && (c.value() < 1 || c.value() > Integer.MAX_VALUE || c.value() != Math.rint(c.value()))) {
            throw new IllegalArgumentException("Expected positive integral count");
        }
    }
    static void validateDuration(Value value, Validation v) {
        Validation.same(value.unit(v), Unit.SECOND);
        if (value instanceof Value.Constant c) {
            long micros = BigDecimal.valueOf(c.value()).movePointRight(6).longValueExact();
            if (micros <= 0 || micros == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid finite duration");
        }
    }
    static int count(Value value, Evaluation e) {
        var number = value.evaluate(e); Validation.same(number.unit(), Unit.COUNT);
        if (number.value() < 1 || number.value() > Integer.MAX_VALUE || number.value() != Math.rint(number.value())) throw new IllegalArgumentException("Expected positive integral count");
        return (int) number.value();
    }
    static long micros(Value value, Evaluation e) {
        var number = value.evaluate(e); Validation.same(number.unit(), Unit.SECOND);
        long micros = BigDecimal.valueOf(number.value()).movePointRight(6).longValueExact();
        if (micros <= 0 || micros == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid finite duration"); return micros;
    }
    record GrantBuff(String buff, Evaluation.Target target, Value stacks, Value tier, Optional<Value> duration) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target);
            v.buff(buff); validateCount(stacks, v); validateCount(tier, v);
            duration.ifPresent(value -> validateDuration(value, v)); return ResultShape.BUFF;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            String holder = e.target(target); var definition = e.definition(buff);
            return local(e, Buffs.grant(e.state().buffs(), definition, holder, holder, e.origin(), count(stacks, e), count(tier, e),
                    duration.map(value -> micros(value, e)).orElse(definition.timer().durationMicros())));
        }
    }
    record ConsumeBuff(String buff, Evaluation.Target target, Value stacks) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target); v.buff(buff); validateCount(stacks, v); return ResultShape.BUFF; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return local(e, Buffs.consume(e.state().buffs(), e.key(buff, target), count(stacks, e))); }
    }
    record RemoveBuff(String buff, Evaluation.Target target) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target); v.buff(buff); return ResultShape.BUFF; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return local(e, Buffs.remove(e.state().buffs(), e.key(buff, target), Buffs.Reason.REMOVED)); }
    }
    record ExtendBuff(String buff, Evaluation.Target target, Value amount, Value cap) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target);
            if (v.buff(buff).timer().mode() != BuffDefinition.TimerMode.SHARED) throw new IllegalArgumentException("Cannot extend independent stack timers");
            validateDuration(amount, v); validateDuration(cap, v); return ResultShape.BUFF;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return local(e, Buffs.extend(e.state().buffs(), e.key(buff, target), micros(amount, e), micros(cap, e))); }
    }
    record RefreshBuff(String buff, Evaluation.Target target, Value duration) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target);
            if (v.buff(buff).timer().mode() != BuffDefinition.TimerMode.SHARED) throw new IllegalArgumentException("Refresh requires a shared timer");
            validateDuration(duration, v); return ResultShape.BUFF;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return local(e, Buffs.refresh(e.state().buffs(), e.key(buff, target), micros(duration, e))); }
    }
    /** Read-only qualification for content reactions, including explicit postmortem reactions. Never grants a buff. */
    record CheckStatus(String buff, Evaluation.Target target, Value stacks, Value tier, Optional<Value> duration, boolean allowDead) implements Action {
        @Override public ResultShape validate(Validation v) { new GrantBuff(buff, target, stacks, tier, duration).validate(v); return ResultShape.STATUS_CHECK; }
        private StatusResult.Check request(Evaluation e) {
            var definition = e.definition(buff);
            return new StatusResult.Check(e.target(target), definition, e.origin(), count(stacks, e), count(tier, e),
                    duration.map(value -> micros(value, e)).orElse(definition.timer().durationMicros()), allowDead);
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(request(e)); }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var checked = (StatusResult.Checked) receipt;
            if (!checked.request().equals(e.context().command(StatusResult.Check.class))) throw new IllegalArgumentException("Status qualification does not match the request");
            return new RuleEngine.Local<>(e.state(), checked, List.of());
        }
    }
    record ApplyStatus(String buff, Evaluation.Target target, Value stacks, Value tier, Optional<Value> duration) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target); new GrantBuff(buff, target, stacks, tier, duration).validate(v); return ResultShape.STATUS; }
        private StatusResult.Check request(Evaluation e) {
            var definition = e.definition(buff);
            return new StatusResult.Check(e.target(target), definition, e.origin(), count(stacks, e), count(tier, e),
                    duration.map(value -> micros(value, e)).orElse(definition.timer().durationMicros()));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(request(e)); }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var checked = (StatusResult.Checked) receipt; var request = e.context().command(StatusResult.Check.class);
            if (!checked.request().equals(request)) throw new IllegalArgumentException("Status authorization does not match the request");
            var key = e.key(buff, target);
            int before = e.state().buffs().active(key).map(BuffInstance::count).orElse(0);
            Optional<String> cause = EffectTimers.event(e.context().event().signal().payload()).flatMap(event -> Optional.ofNullable(event.references().get("damage_id")));
            if (checked.decision() != StatusResult.Decision.ALLOWED) {
                return new RuleEngine.Local<>(e.state(), new StatusResult(cause, false, before, before, Optional.empty(), checked.decision()), List.of());
            }
            var applied = Buffs.grant(e.state().buffs(), request.definition(), request.target(), request.target(), request.source(), request.stacks(), request.tier(), request.duration());
            var instance = applied.store().active(key).orElseThrow();
            var result = new StatusResult(cause, true, before, instance.count(), Optional.of(new EffectState.Lifetime(key, instance.generation())), checked.decision());
            return new RuleEngine.Local<>(e.state().withBuffs(applied.store()), result, applied.signals());
        }
    }
    record CapturedValue(com.imdomestic.chorus.stat.Measure value) implements RuleEngine.ActionResult {
        public CapturedValue { java.util.Objects.requireNonNull(value); }
    }
    record CaptureValue(Value value) implements Action {
        @Override public ResultShape validate(Validation v) {
            var unit = value.unit(v);
            return new ResultShape(java.util.Map.of("value", new ResultShape.Field(unit, result -> {
                var measured = ((CapturedValue) result).value(); Validation.same(measured.unit(), unit); return measured.value();
            })));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Local<>(e.state(), new CapturedValue(value.evaluate(e)), List.of()); }
    }
    record CaptureDamage(Value amount, String damageType, Set<String> tags, Set<String> killTags, boolean nonLethal, String scalingProfile, ActionOrigin origin, Optional<String> shieldScalingProfile, ProcPolicy.Spec proc) implements Action {
        public CaptureDamage { tags = Set.copyOf(tags); killTags = Set.copyOf(killTags); java.util.Objects.requireNonNull(origin); java.util.Objects.requireNonNull(shieldScalingProfile); java.util.Objects.requireNonNull(proc); }
        public CaptureDamage(Value amount, String damageType, Set<String> tags, Set<String> killTags, boolean nonLethal, String scalingProfile, ActionOrigin origin, Optional<String> shieldScalingProfile) {
            this(amount, damageType, tags, killTags, nonLethal, scalingProfile, origin, shieldScalingProfile, ProcPolicy.Spec.DEFAULT);
        }
        public CaptureDamage(Value amount, String damageType, Set<String> tags, Set<String> killTags, boolean nonLethal, String scalingProfile, ActionOrigin origin) {
            this(amount, damageType, tags, killTags, nonLethal, scalingProfile, origin, Optional.empty());
        }
        public CaptureDamage(Value amount, String damageType, Set<String> tags, Set<String> killTags, boolean nonLethal, String scalingProfile) {
            this(amount, damageType, tags, killTags, nonLethal, scalingProfile, ActionOrigin.BOUND);
        }
        @Override public ResultShape validate(Validation v) {
            new Damage(Evaluation.Target.SELF, amount, damageType, tags, killTags, nonLethal, Optional.of(scalingProfile)).validate(v);
            shieldScalingProfile.ifPresent(v::multiplierProfile);
            return ResultShape.DAMAGE_SNAPSHOT;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var value = amount.evaluate(e); Validation.same(value.unit(), Unit.DAMAGE);
            var attack = new DamageCommand(e.self(), origin.resolve(e), value.value(), damageType, tags, killTags, nonLethal, Optional.of(scalingProfile), Optional.empty(), ImpactData.EMPTY, shieldScalingProfile)
                    .withProc(proc.resolve(e.context().event().signal().payload()));
            var snapshot = e.program().orElseThrow(() -> new IllegalStateException("Snapshot action needs its compiled program")).captureDamage(e.state(), attack);
            return new RuleEngine.Local<>(e.state(), snapshot, List.of());
        }
    }
    private static void validateImpact(java.util.Map<String, Value> impact, Validation v) {
        impact.forEach((name, value) -> { ImpactData.requireName(name); value.unit(v); });
    }
    private static ImpactData impact(java.util.Map<String, Value> impact, Evaluation e) {
        var values = new java.util.TreeMap<String, com.imdomestic.chorus.stat.Measure>();
        new java.util.TreeMap<>(impact).forEach((name, value) -> values.put(name, value.evaluate(e)));
        return new ImpactData(values);
    }
    private static DamageCommand prepareDamage(Evaluation e, DamageCommand command) {
        return e.program().map(p -> p.prepareDamage(e.state(), command)).orElse(command);
    }
    private static RuleEngine.Local<EffectState> finishDamage(EffectState state, DamageCommand command, DamageReceipt receipt, java.util.Map<String, String> attribution) {
        if (receipt.observedBuffs().isEmpty() && !receipt.consumptionSettled())
            receipt = receipt.withObservedBuffs(com.imdomestic.chorus.effect.buff.BuffObservation.capture(state.buffs(), command.source().owner(), command.target()));
        var consumed = BuffConsumption.finish(state, command, receipt);
        var signals = new java.util.ArrayList<>(DamageFacts.from(command, receipt, attribution)); signals.addAll(consumed.emitted()); receipt.consumptionFacts().ifPresent(signals::addAll);
        return new RuleEngine.Local<>(consumed.state(), receipt, signals);
    }
    record DamageCaptured(String snapshot, Evaluation.Target target, java.util.Map<String, Value> impact, Optional<String> pellet, Optional<String> batch, Optional<String> group) implements Action {
        public DamageCaptured { impact = java.util.Map.copyOf(impact); java.util.Objects.requireNonNull(pellet); java.util.Objects.requireNonNull(batch); java.util.Objects.requireNonNull(group); }
        public DamageCaptured(String snapshot, Evaluation.Target target, java.util.Map<String, Value> impact, Optional<String> pellet, Optional<String> batch) {
            this(snapshot, target, impact, pellet, batch, Optional.empty());
        }
        public DamageCaptured(String snapshot, Evaluation.Target target, java.util.Map<String, Value> impact, Optional<String> pellet) { this(snapshot, target, impact, pellet, Optional.empty()); }
        public DamageCaptured(String snapshot, Evaluation.Target target, java.util.Map<String, Value> impact) { this(snapshot, target, impact, Optional.empty()); }
        public DamageCaptured(String snapshot, Evaluation.Target target) { this(snapshot, target, java.util.Map.of()); }
        @Override public ResultShape validate(Validation v) {
            v.result(snapshot).requireSnapshot(); BatchActions.validate(batch, v); GroupActions.validate(group, v); v.target(target); validateImpact(impact, v); pellet.ifPresent(name -> v.result(name).requireShotImpact()); return ResultShape.DAMAGE;
        }
        private DamageCommand request(Evaluation e) { return prepareDamage(e, GroupActions.resolve(group, e, BatchActions.resolve(batch, e, e.snapshot(snapshot).command(e.target(target), Action.impact(impact, e))))); }
        private com.imdomestic.chorus.effect.projectile.ProjectileFlight.Impact contact(Evaluation e) {
            return (com.imdomestic.chorus.effect.projectile.ProjectileFlight.Impact) e.context().bindings().get(pellet.orElseThrow());
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var command = request(e);
            if (pellet.isPresent()) com.imdomestic.chorus.effect.projectile.ShotGroups.validateDamage(e.state(), contact(e), command);
            return new RuleEngine.Await<>(command);
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var damage = (DamageReceipt) receipt; var command = e.context().command(DamageCommand.class);
            if (pellet.isEmpty()) return finishDamage(e.state(), command, damage, java.util.Map.of());
            var contact = contact(e); var member = contact.member().orElseThrow();
            var counted = com.imdomestic.chorus.effect.projectile.ShotGroups.damage(e.state(), contact, command, damage);
            var completed = finishDamage(counted, command, damage,
                    java.util.Map.of("shot", member.shot().id(), "pellet", Integer.toString(member.pellet()), "contact", Long.toString(contact.sequence())));
            var signals = new java.util.ArrayList<>(completed.emitted());
            signals.addAll(com.imdomestic.chorus.effect.projectile.ShotGroups.progress(e.state(), counted, member));
            return new RuleEngine.Local<>(completed.state(), completed.result(), signals);
        }
    }
    record Damage(Evaluation.Target target, Value amount, String damageType, Set<String> tags, Set<String> killTags,
            boolean nonLethal, Optional<String> scalingProfile, java.util.Map<String, Value> impact, ActionOrigin origin, Optional<String> shieldScalingProfile, ProcPolicy.Spec proc, Optional<String> batch, Optional<String> group) implements Action {
        public Damage { tags = Set.copyOf(tags); killTags = Set.copyOf(killTags); java.util.Objects.requireNonNull(scalingProfile); impact = java.util.Map.copyOf(impact); java.util.Objects.requireNonNull(origin); java.util.Objects.requireNonNull(shieldScalingProfile); java.util.Objects.requireNonNull(proc); java.util.Objects.requireNonNull(batch); java.util.Objects.requireNonNull(group); }
        public Damage(Evaluation.Target target, Value amount, String damageType, Set<String> tags, Set<String> killTags,
                boolean nonLethal, Optional<String> scalingProfile, java.util.Map<String, Value> impact, ActionOrigin origin, Optional<String> shieldScalingProfile, ProcPolicy.Spec proc, Optional<String> batch) {
            this(target, amount, damageType, tags, killTags, nonLethal, scalingProfile, impact, origin, shieldScalingProfile, proc, batch, Optional.empty());
        }
        public Damage(Evaluation.Target target, Value amount, String damageType, Set<String> tags, Set<String> killTags,
                boolean nonLethal, Optional<String> scalingProfile, java.util.Map<String, Value> impact, ActionOrigin origin, Optional<String> shieldScalingProfile, ProcPolicy.Spec proc) {
            this(target, amount, damageType, tags, killTags, nonLethal, scalingProfile, impact, origin, shieldScalingProfile, proc, Optional.empty());
        }
        public Damage(Evaluation.Target target, Value amount, String damageType, Set<String> tags, Set<String> killTags,
                boolean nonLethal, Optional<String> scalingProfile, java.util.Map<String, Value> impact, ActionOrigin origin, Optional<String> shieldScalingProfile) {
            this(target, amount, damageType, tags, killTags, nonLethal, scalingProfile, impact, origin, shieldScalingProfile, ProcPolicy.Spec.DEFAULT);
        }
        public Damage(Evaluation.Target target, Value amount, String damageType, Set<String> tags, Set<String> killTags,
                boolean nonLethal, Optional<String> scalingProfile, java.util.Map<String, Value> impact, ActionOrigin origin) {
            this(target, amount, damageType, tags, killTags, nonLethal, scalingProfile, impact, origin, Optional.empty());
        }
        public Damage(Evaluation.Target target, Value amount, String damageType, Set<String> tags, Set<String> killTags,
                boolean nonLethal, Optional<String> scalingProfile, java.util.Map<String, Value> impact) {
            this(target, amount, damageType, tags, killTags, nonLethal, scalingProfile, impact, ActionOrigin.BOUND);
        }
        public Damage(Evaluation.Target target, Value amount, String damageType, Set<String> tags, Set<String> killTags,
                boolean nonLethal, Optional<String> scalingProfile) {
            this(target, amount, damageType, tags, killTags, nonLethal, scalingProfile, java.util.Map.of());
        }
        public Damage(Evaluation.Target target, Value amount, String damageType, Set<String> tags, Set<String> killTags, boolean nonLethal) {
            this(target, amount, damageType, tags, killTags, nonLethal, Optional.empty());
        }
        @Override public ResultShape validate(Validation v) { v.target(target); BatchActions.validate(batch, v); GroupActions.validate(group, v);
            Validation.same(amount.unit(v), Unit.DAMAGE);
            if (amount instanceof Value.Constant c) com.imdomestic.chorus.stat.Numbers.nonnegative(c.value(), "damage");
            scalingProfile.ifPresent(v::damageProfile); validateImpact(impact, v);
            shieldScalingProfile.ifPresent(v::multiplierProfile);
            return ResultShape.DAMAGE;
        }
        private DamageCommand request(Evaluation e) {
            var value = amount.evaluate(e); Validation.same(value.unit(), Unit.DAMAGE);
            return prepareDamage(e, GroupActions.resolve(group, e, BatchActions.resolve(batch, e, new DamageCommand(e.target(target), origin.resolve(e), value.value(), damageType, tags, killTags, nonLethal, scalingProfile, Optional.empty(), Action.impact(impact, e), shieldScalingProfile)
                    .withProc(proc.resolve(e.context().event().signal().payload())))));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(request(e)); }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var damage = (DamageReceipt) receipt;
            return finishDamage(e.state(), e.context().command(DamageCommand.class), damage, java.util.Map.of());
        }
    }
    record Heal(Evaluation.Target target, Value amount, Set<String> tags, ActionOrigin origin) implements Action {
        public Heal { tags = Set.copyOf(tags); java.util.Objects.requireNonNull(origin); }
        public Heal(Evaluation.Target target, Value amount, Set<String> tags) { this(target, amount, tags, ActionOrigin.BOUND); }
        @Override public ResultShape validate(Validation v) { v.target(target);
            Validation.same(amount.unit(v), Unit.DAMAGE);
            if (amount instanceof Value.Constant c) com.imdomestic.chorus.stat.Numbers.nonnegative(c.value(), "healing");
            return ResultShape.HEALING;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var value = amount.evaluate(e); Validation.same(value.unit(), Unit.DAMAGE);
            return new RuleEngine.Await<>(new HealingCommand(e.target(target), origin.resolve(e), value.value(), tags));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var healing = (HealingReceipt) receipt;
            if (!healing.command().equals(e.context().command(HealingCommand.class))) throw new IllegalArgumentException("Healing receipt differs from the issued command");
            return new RuleEngine.Local<>(e.state(), healing, HealingFacts.from(healing));
        }
    }
    record RestoreShield(String buff, Evaluation.Target target, Value amount) implements Action {
        @Override public ResultShape validate(Validation v) {
            v.target(target); v.shield(buff); Validation.same(amount.unit(v), Unit.DAMAGE);
            if (amount instanceof Value.Constant constant) com.imdomestic.chorus.stat.Numbers.nonnegative(constant.value(), "shield restoration");
            return ResultShape.SHIELD_RESTORATION;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var key = e.key(buff, target); var instance = e.state().buffs().active(key).orElseThrow(() -> new IllegalArgumentException("Cannot restore a missing shield buff"));
            var program = e.program().orElseThrow(() -> new IllegalStateException("Shield restoration requires a compiled program"));
            var requested = amount.evaluate(e); Validation.same(requested.unit(), Unit.DAMAGE);
            var restored = ShieldRestoration.restore(e.state().buffs(), key, program.shield(buff).capacity(), requested.value(), program.shieldMaximum(e.state(), instance));
            return new RuleEngine.Local<>(e.state().withBuffs(restored.store()), restored.receipt(), ShieldRestoration.facts(restored.receipt(), e.origin()));
        }
    }
    record ResourceGranted(ResourceResult result, BuffInstance.Origin source, String reason) implements EffectEvent.Carrier {
        public ResourceGranted(ResourceResult result, BuffInstance.Origin source) { this(result, source, "grant"); }
        @Override public EffectEvent event() { return ResourceFacts.granted(result, source, reason); }
    }
    record ComponentResult(double before, double after, boolean applied) implements RuleEngine.ActionResult {}
    record UpdateComponent(String buff, Evaluation.Target target, String component, BuffComponents.Update operation, Value value,
            Optional<String> onceSet, Optional<String> eventReference) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target);
            var schema = v.buff(buff).components(); var unit = schema.number(component).unit(); Validation.same(value.unit(v), unit);
            if (onceSet.isPresent() != eventReference.isPresent()) throw new IllegalArgumentException("Deduplication requires both a set and an event reference");
            onceSet.ifPresent(set -> { if (!schema.sets().contains(set)) throw new IllegalArgumentException("Undeclared set component: " + set); });
            return new ResultShape(java.util.Map.of("before", new ResultShape.Field(unit, r -> ((ComponentResult) r).before()),
                    "after", new ResultShape.Field(unit, r -> ((ComponentResult) r).after()),
                    "delta", new ResultShape.Field(unit, r -> ((ComponentResult) r).after() - ((ComponentResult) r).before())),
                    java.util.Map.of("applied", r -> ((ComponentResult) r).applied(), "changed", r -> ((ComponentResult) r).before() != ((ComponentResult) r).after()));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var key = e.key(buff, target);
            var instance = e.state().buffs().active(key).orElseThrow(() -> new IllegalArgumentException("Cannot update a missing buff"));
            var components = instance.components(); double before = components.numbers().get(component);
            if (onceSet.isPresent()) {
                String identity = e.event().references().get(eventReference.orElseThrow());
                if (identity == null || identity.isBlank()) throw new IllegalArgumentException("Missing deduplication identity: " + eventReference.orElseThrow());
                if (components.sets().get(onceSet.orElseThrow()).contains(identity)) {
                    return new RuleEngine.Local<>(e.state(), new ComponentResult(before, before, false), List.of());
                }
                components = components.remember(onceSet.orElseThrow(), identity);
            }
            var amount = value.evaluate(e); Validation.same(amount.unit(), instance.definition().components().number(component).unit());
            components = components.number(component, operation, amount.value());
            return new RuleEngine.Local<>(e.state().withBuffs(Buffs.components(e.state().buffs(), key, components)),
                    new ComponentResult(before, components.numbers().get(component), true), List.of());
        }
    }
    record GrantResource(String resource, Evaluation.Target target, Value requested, Optional<Value> scaled) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target);
            v.resource(resource); Validation.same(requested.unit(v), Unit.CHARGE); scaled.ifPresent(value -> Validation.same(value.unit(v), Unit.CHARGE)); return ResultShape.RESOURCE;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var account = e.resource(resource, target);
            var requestedAmount = requested.evaluate(e); Validation.same(requestedAmount.unit(), Unit.CHARGE);
            var scaledAmount = scaled.map(value -> value.evaluate(e)).orElse(requestedAmount); Validation.same(scaledAmount.unit(), Unit.CHARGE);
            var result = Resources.grant(account, requestedAmount.value(), scaledAmount.value());
            var payload = new ResourceGranted(result, e.origin()); var facts = new java.util.ArrayList<RuleEngine.Signal>();
            facts.add(new RuleEngine.Signal("chorus:resource_granted", payload));
            if (result.after().value() != account.value()) facts.add(new RuleEngine.Signal("chorus:resource_changed", payload));
            return new RuleEngine.Local<>(e.state().withResource(result.after()), result, facts);
        }
    }
    record Initialized(ResourceState account, boolean created) implements RuleEngine.ActionResult {}
    record InitializeResource(String resource, Evaluation.Target target) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target);
            v.resource(resource); return new ResultShape(java.util.Map.of("value", new ResultShape.Field(Unit.CHARGE, result -> ((Initialized) result).account().value())),
                    java.util.Map.of("created", result -> ((Initialized) result).created()));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var definition = e.resourceDefinition(resource); var key = new ResourceState.Key(e.target(target), resource);
            var existing = e.state().resources().get(key);
            if (existing != null) { definition.validate(existing); return new RuleEngine.Local<>(e.state(), new Initialized(existing, false), List.of()); }
            var account = definition.initialize(key.holder(), e.state().buffs().timeMicros());
            var empty = new ResourceState(key, 0, account.capacity(), account.timeMicros());
            return new RuleEngine.Local<>(e.state().withResource(account), new Initialized(account, true),
                    List.of(new RuleEngine.Signal("chorus:resource_initialized", ResourceFacts.change(empty, account, e.origin(), "initialize"))));
        }
    }
    record SpendResource(String resource, Evaluation.Target target, Value amount, String payment) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target);
            v.resource(resource); Validation.same(amount.unit(v), Unit.CHARGE); EffectTimers.localName(payment);
            if (amount instanceof Value.Constant constant) com.imdomestic.chorus.stat.Numbers.nonnegative(constant.value(), "resource cost");
            return ResultShape.RESOURCE_SPEND;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var account = e.resource(resource, target); var value = amount.evaluate(e); Validation.same(value.unit(), Unit.CHARGE);
            String operation = "event/" + e.context().event().id() + "/rule/" + e.context().ruleInstance() + "/payment/" + payment + "/action/" + e.context().operation().frame() + "/"
                    + e.context().operation().pc() + "/" + e.context().operation().invocation();
            var result = Resources.spend(account, operation, value.value());
            var event = ResourceFacts.change(account, result.after(), e.origin(), "spend");
            return new RuleEngine.Local<>(e.state().withResource(result.after()), result,
                    result.receipt().paid() == 0 ? List.of() : List.of(new RuleEngine.Signal("chorus:resource_spent", event), new RuleEngine.Signal("chorus:resource_changed", event)));
        }
    }
    record RefundCost(String cost, Value fraction) implements Action {
        @Override public ResultShape validate(Validation v) {
            v.result(cost).requireCost(); Validation.same(fraction.unit(v), Unit.MULTIPLIER);
            if (fraction instanceof Value.Constant constant) com.imdomestic.chorus.stat.Numbers.fraction(constant.value(), "refund fraction");
            return ResultShape.RESOURCE_REFUND;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var receipt = e.cost(cost); var key = receipt.account(); var account = e.state().resources().get(key);
            if (account == null) throw new IllegalArgumentException("Missing paid resource account: " + key);
            e.resourceDefinition(key.resource()).validate(account);
            var value = fraction.evaluate(e); Validation.same(value.unit(), Unit.MULTIPLIER);
            var result = Resources.refund(account, receipt, value.value());
            var event = ResourceFacts.refunded(result, e.origin()); var facts = new java.util.ArrayList<RuleEngine.Signal>();
            facts.add(new RuleEngine.Signal("chorus:resource_refunded", event));
            if (result.grant().after().value() != account.value()) facts.add(new RuleEngine.Signal("chorus:resource_changed", event));
            return new RuleEngine.Local<>(e.state().withResource(result.grant().after()), result, facts);
        }
    }
    /** Move the remaining local claim into a bounded handle; the old cost is sealed by retention. */
    record RetainCost(String cost, Value duration) implements Action {
        @Override public ResultShape validate(Validation v) { v.result(cost).requireCost(); validateDuration(duration, v); return ResultShape.RETAINED_COST; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var operation = e.context().operation();
            String id = "retained-cost/" + operation.frame() + "/" + operation.pc() + "/" + operation.invocation();
            return RetainedCosts.retain(e.state(), id, e.cost(cost), Math.addExact(e.state().buffs().timeMicros(), micros(duration, e)));
        }
    }
    private static RetainedCosts.Handle retainedCost(Evaluation e, String name) {
        var shape = e.results().get(name); if (shape == null) throw new IllegalArgumentException("Unbound retained cost: " + name);
        shape.requireRetainedCost(); return (RetainedCosts.Handle) e.context().bindings().get(name);
    }
    record RefundRetainedCost(String cost, Value fraction) implements Action {
        @Override public ResultShape validate(Validation v) {
            v.result(cost).requireRetainedCost(); Validation.same(fraction.unit(v), Unit.MULTIPLIER);
            if (fraction instanceof Value.Constant c) com.imdomestic.chorus.stat.Numbers.fraction(c.value(), "refund fraction");
            return ResultShape.RETAINED_REFUND;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var handle = retainedCost(e, cost); var account = e.state().resources().get(handle.receipt().account());
            if (account != null) e.resourceDefinition(account.key().resource()).validate(account);
            var value = fraction.evaluate(e); Validation.same(value.unit(), Unit.MULTIPLIER);
            var transition = RetainedCosts.refund(e.state(), handle, value.value());
            var refund = ((RetainedCosts.Refunded) transition.result()).refund();
            if (refund.isEmpty()) return transition;
            var result = refund.orElseThrow(); var event = ResourceFacts.refunded(result, e.origin()); var facts = new java.util.ArrayList<RuleEngine.Signal>();
            facts.add(new RuleEngine.Signal("chorus:resource_refunded", event));
            if (result.grant().credited() > 0) facts.add(new RuleEngine.Signal("chorus:resource_changed", event));
            return new RuleEngine.Local<>(transition.state(), transition.result(), facts);
        }
    }
    record CloseRetainedCost(String cost) implements Action {
        @Override public ResultShape validate(Validation v) {
            v.result(cost).requireRetainedCost();
            return new ResultShape(java.util.Map.of(), java.util.Map.of("removed", r -> ((RetainedCosts.Closed) r).removed()));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return RetainedCosts.close(e.state(), retainedCost(e, cost)); }
    }
    record GrantFullCharge(String resource, Evaluation.Target target, Value charges) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target);
            v.resource(resource); Validation.same(charges.unit(v), Unit.COUNT);
            if (charges instanceof Value.Constant constant) fullCharges(constant.value());
            return ResultShape.RESOURCE;
        }
        private static int fullCharges(double value) {
            com.imdomestic.chorus.stat.Numbers.nonnegative(value, "full charges");
            if (value > Integer.MAX_VALUE || value != Math.rint(value)) throw new IllegalArgumentException("Expected integral full charges");
            return (int) value;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var account = e.resource(resource, target); var value = charges.evaluate(e); Validation.same(value.unit(), Unit.COUNT);
            var result = Resources.grantFullCharges(account, fullCharges(value.value()));
            var payload = new ResourceGranted(result, e.origin(), "full_charge"); var facts = new java.util.ArrayList<RuleEngine.Signal>();
            facts.add(new RuleEngine.Signal("chorus:resource_granted", payload));
            if (result.after().value() != account.value()) facts.add(new RuleEngine.Signal("chorus:resource_changed", payload));
            return new RuleEngine.Local<>(e.state().withResource(result.after()), result, facts);
        }
    }
    enum TimerPolicy { KEEP, REPLACE, ERROR }
    record TimerResult(boolean applied) implements RuleEngine.ActionResult {}
    record Schedule(String name, String event, Value delay, Optional<Value> interval, int repeat, TimerPolicy policy) implements Action {
        @Override public ResultShape validate(Validation v) {
            EffectTimers.localName(name);
            if (event == null || !event.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || event.startsWith("chorus:internal/")) throw new IllegalArgumentException("Invalid scheduled event");
            validateDuration(delay, v); interval.ifPresent(value -> validateDuration(value, v));
            if (repeat == 0 || repeat < -1 || repeat != 1 && interval.isEmpty()) throw new IllegalArgumentException("Repeating timer requires a positive interval");
            return new ResultShape(java.util.Map.of(), java.util.Map.of("applied", result -> ((TimerResult) result).applied()));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var owner = EffectTimers.Owner.of(e.context().scope());
            if (!owner.active(e.state().sources(), e.state().buffs())) throw new IllegalArgumentException("Cannot schedule for an inactive source");
            String key = owner.key(name); var previous = e.state().timers().get(key);
            if (previous != null) {
                if (policy == TimerPolicy.ERROR) throw new IllegalArgumentException("Timer already exists: " + key);
                if (policy == TimerPolicy.KEEP) return new RuleEngine.Local<>(e.state(), new TimerResult(false), List.of());
            }
            long after = micros(delay, e), period = interval.map(value -> micros(value, e)).orElse(0L);
            boolean paused = owner.buff().isPresent() && e.ownBuff().pausedAt().isPresent();
            var signal = new RuleEngine.Signal(event, new EffectTimers.Scheduled(name, owner, e.timerEvent()));
            var timer = new EffectState.Timer(key, paused ? Long.MAX_VALUE : Math.addExact(e.state().buffs().timeMicros(), after), period, repeat,
                    signal, owner.buff(), paused ? java.util.OptionalLong.of(after) : java.util.OptionalLong.empty());
            return new RuleEngine.Local<>(e.state().cancel(key).schedule(timer), new TimerResult(true), List.of());
        }
    }
    record CancelTimer(String name) implements Action {
        @Override public ResultShape validate(Validation v) {
            EffectTimers.localName(name);
            return new ResultShape(java.util.Map.of(), java.util.Map.of("applied", result -> ((TimerResult) result).applied()));
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            String key = EffectTimers.Owner.of(e.context().scope()).key(name);
            return new RuleEngine.Local<>(e.state().cancel(key), new TimerResult(e.state().timers().containsKey(key)), List.of());
        }
    }
    record Emit(String event) implements Action {
        @Override public ResultShape validate(Validation v) {
            if (event.startsWith("chorus:internal/")) throw new IllegalArgumentException("Reserved internal event"); return ResultShape.EMPTY;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            return new RuleEngine.Local<>(e.state(), RuleEngine.Empty.INSTANCE, List.of(new RuleEngine.Signal(event, e.context().event().signal().payload())));
        }
    }
    record CueCommand(String cue, String target, BuffInstance.Origin source) implements RuleEngine.WorldCommand {}
    private static com.imdomestic.chorus.effect.target.Targets.Identities targetSet(BuffInstance instance, String name) {
        var result = instance.components().targetSets().get(name);
        if (result == null) throw new IllegalArgumentException("Missing target set component: " + name);
        return result;
    }
    record ReadTargets(String buff, Evaluation.Target target, String component) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target); v.buff(buff).components().requireTargets(component); return ResultShape.TARGET_IDENTITIES; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            return new RuleEngine.Local<>(e.state(), targetSet(e.readBuff(buff, target), component), List.of());
        }
    }
    record SyncTargets(String buff, Evaluation.Target target, String component, String targets) implements Action {
        @Override public ResultShape validate(Validation v) {
            v.target(target); v.buff(buff).components().requireTargets(component); v.result(targets).requireTargets(); return ResultShape.TARGET_DIFFERENCE;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var key = e.key(buff, target); var instance = e.state().buffs().active(key).orElseThrow(() -> new IllegalArgumentException("Cannot update a missing buff"));
            var before = targetSet(instance, component); var result = e.context().bindings().get(targets);
            boolean observed = !(result instanceof com.imdomestic.chorus.effect.target.TargetQuery.Result query)
                    || query.outcome() == com.imdomestic.chorus.effect.target.TargetQuery.Outcome.AVAILABLE;
            var after = observed ? com.imdomestic.chorus.effect.target.Targets.Identities.of(e.results().get(targets).targets(result).stream()
                    .map(com.imdomestic.chorus.effect.target.Targets.Reference::entity).toList()) : before;
            var difference = new com.imdomestic.chorus.effect.target.Targets.Difference(observed, before, after);
            var state = difference.changed() ? e.state().withBuffs(Buffs.components(e.state().buffs(), key, instance.components().targets(component, after))) : e.state();
            return new RuleEngine.Local<>(state, difference, List.of());
        }
    }
    record DifferenceTargets(String binding, com.imdomestic.chorus.effect.target.Targets.Part part) implements Action {
        @Override public ResultShape validate(Validation v) { v.result(binding).requireTargetDifference(); return ResultShape.TARGET_IDENTITIES; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var difference = (com.imdomestic.chorus.effect.target.Targets.Difference) e.context().bindings().get(binding);
            return new RuleEngine.Local<>(e.state(), difference.part(part), List.of());
        }
    }
    /** Reuse typed entity results without issuing a new world query or substituting current state. */
    record ReadEventEntity(Evaluation.Target target) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target); return ResultShape.ENTITY; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            String holder = e.target(target);
            var observation = e.event().observedEntities().orElseThrow(() -> new IllegalArgumentException("Event has no entity observation"));
            return new RuleEngine.Local<>(e.state(), new com.imdomestic.chorus.effect.target.EntityQuery.Result(
                    new com.imdomestic.chorus.effect.target.EntityQuery(holder), observation.require(holder)), List.of());
        }
    }
    record InspectEntity(Evaluation.Target target) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target); return ResultShape.ENTITY; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            return new RuleEngine.Await<>(new com.imdomestic.chorus.effect.target.EntityQuery(e.target(target)));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var observation = (com.imdomestic.chorus.effect.target.EntityQuery.Result) receipt;
            if (!observation.query().equals(e.context().command(com.imdomestic.chorus.effect.target.EntityQuery.class))) throw new IllegalArgumentException("Entity observation does not match the request");
            return new RuleEngine.Local<>(e.state(), observation, List.of());
        }
    }
    record ReadPosition(String buff, Evaluation.Target target, String component) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target); v.buff(buff).components().requirePosition(component); return ResultShape.POSITION; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var position = e.readBuff(buff, target).components().positions().get(component);
            if (position == null) throw new IllegalArgumentException("Missing position component: " + component);
            return new RuleEngine.Local<>(e.state(), new com.imdomestic.chorus.effect.target.PositionResult.Stored(position), List.of());
        }
    }
    /** Exact copy of a typed position, including explicit absence; this action performs no world read. */
    record WritePosition(String buff, Evaluation.Target target, String component, String position) implements Action {
        @Override public ResultShape validate(Validation v) {
            v.target(target); v.buff(buff).components().requirePosition(component); v.result(position).requirePosition(); return ResultShape.POSITION;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var key = e.key(buff, target); var instance = e.state().buffs().active(key).orElseThrow(() -> new IllegalArgumentException("Cannot update a missing buff"));
            var value = e.results().get(position).position(e.context().bindings().get(position));
            var components = instance.components().position(component, value);
            var state = components.equals(instance.components()) ? e.state() : e.state().withBuffs(Buffs.components(e.state().buffs(), key, components));
            return new RuleEngine.Local<>(state, new com.imdomestic.chorus.effect.target.PositionResult.Stored(value), List.of());
        }
    }
    record ReadDamageSnapshot(String buff, Evaluation.Target target, String component) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target); v.buff(buff).components().requireDamageSnapshot(component); return ResultShape.STORED_DAMAGE_SNAPSHOT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var snapshot = e.readBuff(buff, target).components().damageSnapshots().get(component);
            if (snapshot == null) throw new IllegalArgumentException("Missing damage snapshot component: " + component);
            return new RuleEngine.Local<>(e.state(), new DamageSnapshot.Stored(snapshot), List.of());
        }
    }
    record WriteDamageSnapshot(String buff, Evaluation.Target target, String component, String snapshot) implements Action {
        @Override public ResultShape validate(Validation v) {
            v.target(target); v.buff(buff).components().requireDamageSnapshot(component); v.result(snapshot).requireSnapshot(); return ResultShape.STORED_DAMAGE_SNAPSHOT;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var key = e.key(buff, target); var instance = e.state().buffs().active(key).orElseThrow(() -> new IllegalArgumentException("Cannot update a missing buff"));
            var value = e.results().get(snapshot).optionalSnapshot(e.context().bindings().get(snapshot));
            var components = instance.components().damageSnapshot(component, value);
            var state = components.equals(instance.components()) ? e.state() : e.state().withBuffs(Buffs.components(e.state().buffs(), key, components));
            return new RuleEngine.Local<>(state, new DamageSnapshot.Stored(value), List.of());
        }
    }
    /** Historical position value, with no new world read or entity availability claim. */
    record ReadEventPosition(Evaluation.Target target, com.imdomestic.chorus.effect.target.TargetQuery.Anchor anchor) implements Action {
        public ReadEventPosition { java.util.Objects.requireNonNull(target); java.util.Objects.requireNonNull(anchor); }
        @Override public ResultShape validate(Validation v) { v.target(target); return ResultShape.POSITION; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var observation = e.event().observedEntities().orElseThrow(() -> new IllegalArgumentException("Event has no position observation"));
            return new RuleEngine.Local<>(e.state(), new com.imdomestic.chorus.effect.target.PositionResult.Stored(
                    observation.requirePosition(e.target(target), anchor)), List.of());
        }
    }
    record CapturePosition(Evaluation.Target target, com.imdomestic.chorus.effect.target.TargetQuery.Anchor anchor) implements Action {
        public CapturePosition(Evaluation.Target target) { this(target, com.imdomestic.chorus.effect.target.TargetQuery.Anchor.FEET); }
        @Override public ResultShape validate(Validation v) { v.target(target); return ResultShape.POSITION; }
        private com.imdomestic.chorus.effect.target.PositionQuery request(Evaluation e) {
            return new com.imdomestic.chorus.effect.target.PositionQuery(e.target(target), anchor);
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(request(e)); }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var captured = (com.imdomestic.chorus.effect.target.PositionQuery.Result) receipt;
            if (!captured.query().equals(e.context().command(com.imdomestic.chorus.effect.target.PositionQuery.class))) throw new IllegalArgumentException("Position receipt does not match the request");
            return new RuleEngine.Local<>(e.state(), captured, List.of());
        }
    }
    record CaptureDirection(Evaluation.Target target) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target); return ResultShape.DIRECTION; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(new com.imdomestic.chorus.effect.target.DirectionQuery(e.target(target))); }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var result = (com.imdomestic.chorus.effect.target.DirectionQuery.Result) receipt;
            if (!result.query().equals(e.context().command(com.imdomestic.chorus.effect.target.DirectionQuery.class))) throw new IllegalArgumentException("Direction receipt does not match request");
            return new RuleEngine.Local<>(e.state(), result, List.of());
        }
    }
    record SelectTargets(Evaluation.Center center, TargetArea area, com.imdomestic.chorus.effect.target.TargetQuery.Relation relation,
            Evaluation.Target relativeTo, boolean includeCenter, List<Evaluation.Target> exclude,
            com.imdomestic.chorus.effect.target.TargetQuery.Order order, Optional<Value> limit, com.imdomestic.chorus.effect.target.TargetQuery.Anchor centerAnchor,
            com.imdomestic.chorus.effect.target.TargetQuery.Anchor targetAnchor, boolean lineOfSight) implements Action {
        public SelectTargets { exclude = List.copyOf(exclude); java.util.Objects.requireNonNull(order); java.util.Objects.requireNonNull(limit); }
        public SelectTargets(Evaluation.Center center, Value radius, com.imdomestic.chorus.effect.target.TargetQuery.Relation relation,
                Evaluation.Target relativeTo, boolean includeCenter, List<Evaluation.Target> exclude, com.imdomestic.chorus.effect.target.TargetQuery.Order order, Optional<Value> limit) {
            this(center, new TargetArea.Sphere(radius), relation, relativeTo, includeCenter, exclude, order, limit,
                    com.imdomestic.chorus.effect.target.TargetQuery.Anchor.FEET, com.imdomestic.chorus.effect.target.TargetQuery.Anchor.FEET, false);
        }
        public SelectTargets(Evaluation.Center center, Value radius, com.imdomestic.chorus.effect.target.TargetQuery.Relation relation,
                Evaluation.Target relativeTo, boolean includeCenter) {
            this(center, radius, relation, relativeTo, includeCenter, List.of(), com.imdomestic.chorus.effect.target.TargetQuery.Order.IDENTITY, Optional.empty());
        }
        @Override public ResultShape validate(Validation v) {
            v.center(center); v.target(relativeTo); area.validate(v);
            if (center instanceof Evaluation.BoundPosition && centerAnchor != com.imdomestic.chorus.effect.target.TargetQuery.Anchor.FEET) throw new IllegalArgumentException("Captured positions already specify the exact center");
            exclude.forEach(v::target);
            limit.ifPresent(value -> {
                Validation.same(value.unit(v), Unit.COUNT);
                if (value instanceof Value.Constant c) targetLimit(c.value());
            });
            return ResultShape.TARGETS;
        }
        private static int targetLimit(double value) {
            com.imdomestic.chorus.stat.Numbers.nonnegative(value, "target limit");
            if (value != Math.rint(value) || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Target limit must be an integral count");
            return (int) value;
        }
        private com.imdomestic.chorus.effect.target.TargetQuery request(Evaluation e) {
            var shape = area.resolve(e); var resolvedCenter = e.center(center);
            if (resolvedCenter instanceof com.imdomestic.chorus.effect.target.TargetQuery.EntityCenter entity) resolvedCenter = new com.imdomestic.chorus.effect.target.TargetQuery.EntityCenter(entity.entity(), centerAnchor);
            var cap = limit.map(expression -> {
                var measured = expression.evaluate(e); Validation.same(measured.unit(), Unit.COUNT); return java.util.OptionalInt.of(targetLimit(measured.value()));
            }).orElse(java.util.OptionalInt.empty());
            var excluded = exclude.stream().map(e::target).collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new com.imdomestic.chorus.effect.target.TargetQuery(resolvedCenter, shape, relation, e.target(relativeTo), includeCenter, excluded, order, cap, targetAnchor, lineOfSight);
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(request(e)); }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var selected = (com.imdomestic.chorus.effect.target.TargetQuery.Result) receipt;
            if (!selected.query().equals(e.context().command(com.imdomestic.chorus.effect.target.TargetQuery.class))) throw new IllegalArgumentException("Target query receipt does not match the request");
            return new RuleEngine.Local<>(e.state(), selected, List.of());
        }
    }
    record Cue(String cue, Evaluation.Target target) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(target); return ResultShape.EMPTY; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(new CueCommand(cue, e.target(target), e.origin())); }
    }
}
