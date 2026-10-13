package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.EffectTimers;
import com.imdomestic.chorus.effect.ability.AbilityDefinition;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.buff.BuffRules;
import com.imdomestic.chorus.effect.buff.Buffs;
import java.util.List;
import java.util.Optional;

public interface Condition {
    void validate(Validation validation);
    boolean test(Evaluation evaluation);
    /** Partial evaluation for an attack at use time; no world reads or retained executable closures. */
    default Condition snapshot(Evaluation evaluation) { return SnapshotExpressions.condition(this, evaluation); }
    record Constant(boolean value) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) { return value; }
    }
    record All(List<Condition> of) implements Condition {
        public All { of = List.copyOf(of); }
        @Override public void validate(Validation v) { of.forEach(c -> c.validate(v)); }
        @Override public boolean test(Evaluation e) { return of.stream().allMatch(c -> c.test(e)); }
    }
    record Any(List<Condition> of) implements Condition {
        public Any { of = List.copyOf(of); }
        @Override public void validate(Validation v) { of.forEach(c -> c.validate(v)); }
        @Override public boolean test(Evaluation e) { return of.stream().anyMatch(c -> c.test(e)); }
    }
    record Not(Condition value) implements Condition {
        @Override public void validate(Validation v) { value.validate(v); }
        @Override public boolean test(Evaluation e) { return !value.test(e); }
    }
    enum Source { THIS_WEAPON, THIS_ABILITY, OWNER }
    /** Read canonical base or immediately replaced cast identity; neither inherits incidental input tags. */
    record CastAbilityTag(String reference, String tag) implements Condition {
        public CastAbilityTag {
            if (!java.util.Set.of("base_ability", "replaced_ability").contains(reference)) throw new IllegalArgumentException("Invalid cast ability reference: " + reference);
            AbilityDefinition.id(tag);
        }
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            String id = e.event().references().getOrDefault(reference, "");
            return !id.isEmpty() && e.program().orElseThrow(() -> new IllegalStateException("Cast ability metadata requires a compiled program")).abilityHasTag(id, tag);
        }
    }
    /** Read the committed base selection, independently of cast replacements and retained event credit. */
    record SelectedAbilityTag(String slot, String tag, Evaluation.Target target) implements Condition {
        public SelectedAbilityTag {
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(slot);
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(tag);
            java.util.Objects.requireNonNull(target);
        }
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) {
            return e.program().orElseThrow(() -> new IllegalStateException("Selected ability requires a compiled program"))
                    .selectedAbility(e.state(), e.target(target), slot).filter(ability -> ability.tags().contains(tag)).isPresent();
        }
    }
    /** Compare the selected base ability's receiving account, without resolving cast replacements or payment aliases. */
    record SelectedAbilityResource(String slot, String resource, Evaluation.Target target) implements Condition {
        public SelectedAbilityResource {
            AbilityDefinition.id(slot); AbilityDefinition.id(resource); java.util.Objects.requireNonNull(target);
        }
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) {
            return e.program().orElseThrow(() -> new IllegalStateException("Selected ability requires a compiled program"))
                    .selectedAbility(e.state(), e.target(target), slot).flatMap(AbilityDefinition::energyResource).filter(resource::equals).isPresent();
        }
    }
    /** Resource-rate/gain events name the queried account in a reference; snapshots bind that identity separately. */
    record SelectedAbilityEventResource(String slot, String reference, Evaluation.Target target) implements Condition {
        public SelectedAbilityEventResource {
            AbilityDefinition.id(slot); java.util.Objects.requireNonNull(target); EffectTimers.localName(reference);
        }
        @Override public void validate(Validation v) { v.target(target); }
        Condition resolve(Evaluation e) {
            String resource = e.event().references().get(reference);
            return resource == null || !resource.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? new Constant(false) : new SelectedAbilityResource(slot, resource, target);
        }
        @Override public boolean test(Evaluation e) { return resolve(e).test(e); }
    }
    /** Held input includes entering ADS; ready requires the host-confirmed completed transition. */
    record WeaponAiming(Evaluation.Target target, boolean ready) implements Condition {
        public WeaponAiming { java.util.Objects.requireNonNull(target); }
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) {
            var aim = e.state().aiming().get(e.target(target));
            return aim != null && aim.held() && (!ready || aim.phase() == com.imdomestic.chorus.effect.weapon.WeaponAim.Phase.AIMED);
        }
    }
    record WeaponDrawn() implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            var origin = e.origin(); var loadout = e.state().equipment().get(origin.owner());
            return loadout != null && !origin.weapon().isBlank() && loadout.drawn().map(slot -> loadout.slots().get(slot).instance().equals(origin.weapon())).orElse(false);
        }
    }
    record OwnSource() implements Condition {
        @Override public void validate(Validation v) { if (v.buffSource()) throw new IllegalArgumentException("Own source requires a static bundle"); }
        @Override public boolean test(Evaluation e) {
            if (!(e.context().scope() instanceof com.imdomestic.chorus.effect.EffectSource source)) return false;
            if (e.context().event().signal().payload() instanceof com.imdomestic.chorus.effect.SourceChange.Fact fact) return source.equals(fact.source());
            return EffectTimers.event(e.context().event().signal().payload()).filter(event -> source.instance().equals(event.references().get("source_instance"))
                    && source.bundle().equals(event.references().get("bundle"))).isPresent();
        }
    }
    record SourceIs(Source source) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            var payload = EffectTimers.event(e.context().event().signal().payload());
            if (payload.isEmpty()) return false;
            var event = payload.orElseThrow();
            String actual = switch (source) { case THIS_WEAPON -> event.source().weapon(); case THIS_ABILITY -> event.source().ability(); case OWNER -> event.source().owner(); };
            String expected = switch (source) { case THIS_WEAPON -> e.origin().weapon(); case THIS_ABILITY -> e.origin().ability(); case OWNER -> e.origin().owner(); };
            return !expected.isEmpty() && expected.equals(actual);
        }
    }
    record EventTag(String tag) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) { return EffectTimers.event(e.context().event().signal().payload()).filter(event -> event.tags().contains(tag)).isPresent(); }
    }
    /** Classification captured from the accepted resolved ability, excluding incidental input tags. */
    record EventAbilityTag(String tag) implements Condition {
        public EventAbilityTag { AbilityDefinition.id(tag); }
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            var signal = e.context().event().signal();
            return switch (signal.payload()) {
                case AbilityUse.Started used ->
                        signal.type().equals(AbilityUse.STARTED) && matches(used.definition(), used.receipt());
                case AbilityUse.Used used ->
                        signal.type().equals("chorus:ability_used") && matches(used.definition(), used.receipt());
                default -> false;
            };
        }
        private boolean matches(AbilityDefinition definition, AbilityUse.Receipt receipt) {
            return receipt.outcome() == AbilityUse.Outcome.ACCEPTED
                    && definition.id().equals(receipt.resolved()) && definition.tags().contains(tag);
        }
    }
    record LayerTag(String tag) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            return e.context().event().signal().payload() instanceof com.imdomestic.chorus.effect.combat.ShieldQuery query
                    && query.layer().definition().tags().contains(tag);
        }
    }
    record SourceTag(String tag) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) { return e.origin().tags().contains(tag); }
    }
    record EventSourceTag(String tag) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            return EffectTimers.event(e.context().event().signal().payload()).filter(event -> event.source().tags().contains(tag)).isPresent();
        }
    }
    record EventReference(String name, String expected) implements Condition {
        public EventReference {
            if (name == null || name.isBlank() || expected == null || expected.isBlank()) throw new IllegalArgumentException("Missing event reference comparison");
        }
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            return EffectTimers.event(e.context().event().signal().payload()).filter(event -> expected.equals(event.references().get(name))).isPresent();
        }
    }
    /** Exact membership in this source's active buff instance; no cross-source search or mutation. */
    record SetContains(String buff, Evaluation.Target target, String component, String entry) implements Condition {
        public SetContains {
            java.util.Objects.requireNonNull(target);
            if (buff == null || buff.isBlank() || component == null || component.isBlank() || entry == null || entry.isBlank())
                throw new IllegalArgumentException("Missing set membership identity");
        }
        @Override public void validate(Validation v) {
            v.target(target);
            if (!v.buff(buff).components().sets().contains(component)) throw new IllegalArgumentException("Undeclared set component: " + component);
        }
        @Override public boolean test(Evaluation e) {
            return e.state().buffs().active(e.key(buff, target)).map(b -> b.components().sets().get(component).contains(entry)).orElse(false);
        }
    }
    /** Resolve the event identity now, retaining only that string when snapshotting a live victim query. */
    record SetContainsEventReference(String buff, Evaluation.Target target, String component, String eventReference) implements Condition {
        public SetContainsEventReference { new SetContains(buff, target, component, eventReference); }
        @Override public void validate(Validation v) { new SetContains(buff, target, component, eventReference).validate(v); }
        SetContains resolve(Evaluation e) {
            String identity = e.event().references().get(eventReference);
            if (identity == null || identity.isBlank()) throw new IllegalArgumentException("Missing set identity: " + eventReference);
            return new SetContains(buff, target, component, identity);
        }
        @Override public boolean test(Evaluation e) { return resolve(e).test(e); }
    }
    enum Crossing { UP, DOWN }
    record ResourceCrossed(String resource, Evaluation.Target target, double threshold, Crossing direction) implements Condition {
        @Override public void validate(Validation v) { v.target(target); v.resource(resource).threshold(threshold); }
        @Override public boolean test(Evaluation e) {
            var event = EffectTimers.event(e.context().event().signal().payload());
            if (event.isEmpty() || !resource.equals(event.orElseThrow().references().get("resource"))
                    || !e.target(target).equals(event.orElseThrow().victim())) return false;
            var before = event.orElseThrow().numbers().get("before"); var after = event.orElseThrow().numbers().get("after");
            if (before == null || after == null) throw new IllegalArgumentException("Resource change is missing boundary measurements");
            Validation.same(before.unit(), com.imdomestic.chorus.stat.Unit.CHARGE); Validation.same(after.unit(), com.imdomestic.chorus.stat.Unit.CHARGE);
            return direction == Crossing.UP ? before.value() < threshold && after.value() >= threshold : before.value() > threshold && after.value() <= threshold;
        }
    }
    record TargetIs(Evaluation.Target left, Evaluation.Target right) implements Condition {
        @Override public void validate(Validation v) { v.target(left); v.target(right);}
        @Override public boolean test(Evaluation e) { return e.target(left).equals(e.target(right)); }
    }
    record TargetRefPresent(Evaluation.Target target) implements Condition {
        public TargetRefPresent { java.util.Objects.requireNonNull(target); }
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) { return e.targetReference(target).isPresent(); }
    }
    record EventFlag(String name, boolean expected) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            Boolean flag = e.event().flags().get(name);
            if (flag == null) throw new IllegalArgumentException("Missing event flag: " + name);
            return flag == expected;
        }
    }
    record ResultFlag(String binding, String field, boolean expected) implements Condition {
        @Override public void validate(Validation v) { v.result(binding).requireFlag(field); }
        @Override public boolean test(Evaluation e) {
            var result = e.context().bindings().get(binding);
            if (result == null) throw new IllegalArgumentException("Unbound result: " + binding);
            return e.results().get(binding).flag(field, result) == expected;
        }
    }
    record ObservedEntityTag(String binding, com.imdomestic.chorus.effect.target.EntityQuery.TagSource source, String tag) implements Condition {
        public ObservedEntityTag {
            if (binding == null || binding.isBlank() || tag == null || tag.isBlank()) throw new IllegalArgumentException("Missing entity observation binding or tag");
            java.util.Objects.requireNonNull(source);
        }
        @Override public void validate(Validation v) { v.result(binding).requireEntityObservation(); }
        @Override public boolean test(Evaluation e) {
            var result = e.context().bindings().get(binding);
            if (result == null) throw new IllegalArgumentException("Unbound result: " + binding);
            return e.results().get(binding).entityTag(result, source, tag);
        }
    }
    record AbilityEnergyFlag(String slot, Evaluation.Target target, String field, boolean expected) implements Condition {
        public AbilityEnergyFlag {
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(slot);
            java.util.Objects.requireNonNull(target); java.util.Objects.requireNonNull(field);
        }
        @Override public void validate(Validation v) { v.target(target); EnergyActions.ABILITY_OBSERVATION.requireFlag(field); }
        @Override public boolean test(Evaluation e) { return EnergyActions.ABILITY_OBSERVATION.flag(field, EnergyActions.observeAbility(e, slot, target)) == expected; }
    }
    record EventPositionObserved(Evaluation.Target target, com.imdomestic.chorus.effect.target.TargetQuery.Anchor anchor) implements Condition {
        public EventPositionObserved { java.util.Objects.requireNonNull(target); java.util.Objects.requireNonNull(anchor); }
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) { return e.event().observedEntities().filter(o -> o.positionObserved(e.target(target), anchor)).isPresent(); }
    }
    record EventEntityObserved(Evaluation.Target target) implements Condition {
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) { return e.event().observedEntities().filter(o -> o.observed(e.target(target))).isPresent(); }
    }
    record EventBuffsAvailable(Evaluation.Target target) implements Condition {
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) { return e.event().observedBuffs().filter(b -> b.available(e.target(target))).isPresent(); }
    }
    record EventHasBuff(String buff, Evaluation.Target target, int minimum, BuffMatch match) implements Condition {
        @Override public void validate(Validation v) { new HasBuff(buff, target, minimum, match).validate(v); }
        @Override public boolean test(Evaluation e) {
            var entries = eventBuffs(e, target);
            return entries.stream().anyMatch(b -> b.key().definition().equals(buff) && b.stacks() >= minimum
                    && (match == BuffMatch.ANY || b.key().equals(e.key(buff, target)))
                    && (match != BuffMatch.INSTANCE || b.generation() == e.ownBuff().generation()));
        }
    }
    record EventHasBuffTag(String tag, Evaluation.Target target) implements Condition {
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) { return eventBuffs(e, target).stream().anyMatch(b -> b.tags().contains(tag)); }
    }
    private static List<com.imdomestic.chorus.effect.buff.BuffObservation.Entry> eventBuffs(Evaluation e, Evaluation.Target target) {
        return e.event().observedBuffs().orElseThrow(() -> new IllegalArgumentException("Event has no buff observation")).require(e.target(target));
    }
    enum BuffMatch { BOUND, ANY, INSTANCE }
    record HasBuff(String buff, Evaluation.Target target, int minimum, BuffMatch match) implements Condition {
        public HasBuff { java.util.Objects.requireNonNull(match); }
        public HasBuff(String buff, Evaluation.Target target, int minimum) { this(buff, target, minimum, BuffMatch.BOUND); }
        @Override public void validate(Validation v) { v.target(target); v.buff(buff); if (match == BuffMatch.INSTANCE) v.requireBuffSource(); if (minimum < 0) throw new IllegalArgumentException("Invalid minimum stacks"); }
        @Override public boolean test(Evaluation e) {
            if (match != BuffMatch.ANY) return e.state().buffs().active(e.key(buff, target))
                    .filter(value -> value.quantity() >= minimum && (match != BuffMatch.INSTANCE || value.generation() == e.ownBuff().generation())).isPresent();
            String holder = e.target(target);
            return e.state().buffs().instances().values().stream().anyMatch(value -> value.key().holder().equals(holder) && value.definition().id().equals(buff)
                    && value.activeQuantity(e.state().buffs().timeMicros()) > 0 && value.activeQuantity(e.state().buffs().timeMicros()) >= minimum);
        }
    }
    /** Query currently bound sources on a holder, independently of this action's retained origin. */
    record HasSourceTag(String tag, Evaluation.Target target) implements Condition {
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) {
            String holder = e.target(target);
            return e.state().sources().values().stream().anyMatch(source -> source.holder().equals(holder)
                    && (source.tags().contains(tag) || source.origin().tags().contains(tag)));
        }
    }
    record HasBuffTag(String tag, Evaluation.Target target) implements Condition {
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) {
            String holder = e.target(target);
            return e.state().buffs().instances().values().stream().anyMatch(value -> value.key().holder().equals(holder)
                    && value.activeCount(e.state().buffs().timeMicros()) > 0 && value.definition().tags().contains(tag));
        }
    }
    record HasShield(java.util.Optional<String> tag, Evaluation.Target target) implements Condition {
        public HasShield { java.util.Objects.requireNonNull(tag); }
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) { return e.program().orElseThrow(() -> new IllegalStateException("Shield query requires a compiled program")).hasShield(e.state(), e.target(target), tag); }
    }
    enum Comparison { EQ, NE, LT, LE, GT, GE }
    record Compare(Value left, Comparison operation, Value right) implements Condition {
        @Override public void validate(Validation v) { Validation.same(left.unit(v), right.unit(v)); }
        @Override public boolean test(Evaluation e) {
            var a = left.evaluate(e); var b = right.evaluate(e); Validation.same(a.unit(), b.unit());
            return switch (operation) { case EQ -> a.value() == b.value(); case NE -> a.value() != b.value(); case LT -> a.value() < b.value();
                case LE -> a.value() <= b.value(); case GT -> a.value() > b.value(); case GE -> a.value() >= b.value(); };
        }
    }
    /** A layer fact belongs to this exact generation, even if its key has since been reused. */
    record OwnShield() implements Condition {
        @Override public void validate(Validation v) { v.requireBuffSource(); }
        @Override public boolean test(Evaluation e) {
            if (!(e.context().scope() instanceof BuffRules.Scope scope)) return false;
            var instance = scope.snapshot();
            return EffectTimers.event(e.context().event().signal().payload()).filter(event ->
                    instance.key().holder().equals(event.victim())
                    && instance.definition().id().equals(event.references().get("shield_definition"))
                    && Long.toString(instance.generation()).equals(event.references().get("shield_generation"))).isPresent();
        }
    }
    record OwnBuff(Optional<Buffs.Reason> reason) implements Condition {
        @Override public void validate(Validation v) { v.requireBuffSource(); }
        @Override public boolean test(Evaluation e) {
            return e.context().scope() instanceof BuffRules.Scope scope && e.context().event().signal().payload() instanceof Buffs.Change change
                    && scope.owns(change) && (reason.isEmpty() || reason.orElseThrow() == change.reason());
        }
    }
    record OwnTimer(String name) implements Condition {
        @Override public void validate(Validation v) { EffectTimers.localName(name); }
        @Override public boolean test(Evaluation e) {
            return e.context().event().signal().payload() instanceof EffectTimers.Scheduled scheduled && scheduled.name().equals(name)
                    && scheduled.owner().equals(EffectTimers.Owner.of(e.context().scope()));
        }
    }
}
