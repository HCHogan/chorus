package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Pure selection phases. Only their explicit world command changes the physical drawn slot. */
final class CompiledHandling {
    private CompiledHandling() {}
    static boolean configured(EffectState state, String holder, Optional<String> slot, CompiledWeapons weapons, CompiledEffects program) {
        var loadout = state.equipment().getOrDefault(holder, Loadout.EMPTY);
        var desired = new Loadout(loadout.slots(), slot); program.equipment().validate(desired);
        return state.handling().containsKey(holder) || List.of(loadout, desired).stream().map(WeaponReload::drawn)
                .flatMap(Optional::stream).map(g -> weapons.definition(g.definition())).filter(Objects::nonNull).anyMatch(w -> w.handling().isPresent());
    }
    static RuleEngine.Local<EffectState> begin(EffectState state, WeaponHandling.Request request, CompiledWeapons weapons, CompiledEffects program) {
        var loadout = state.equipment().getOrDefault(request.holder(), Loadout.EMPTY);
        program.equipment().validate(new Loadout(loadout.slots(), request.slot()));
        var previous = state.handling().get(request.holder());
        if (previous != null && previous.targetSlot().equals(request.slot()) || previous == null && loadout.drawn().equals(request.slot()))
            return result(state, WeaponHandling.Outcome.UNCHANGED);
        var updated = state; var facts = new ArrayList<RuleEngine.Signal>();
        if (previous != null) {
            var cancelled = cancel(updated, previous, "new_request"); updated = cancelled.state(); facts.addAll(cancelled.emitted());
        }
        if (loadout.drawn().equals(request.slot()))
            return new RuleEngine.Local<>(updated, new WeaponHandling.Receipt(WeaponHandling.Outcome.CANCELLED, Optional.empty()), facts);
        var reload = weapons.interruptReload(updated, request.holder(), "switch_accepted"); updated = reload.state(); facts.addAll(reload.emitted());
        var target = request.slot().map(loadout.slots()::get);
        var phase = loadout.drawn().isPresent() ? WeaponHandling.Phase.STOW : WeaponHandling.Phase.READY;
        var plan = plan(updated, request.holder(), request.token(), request.slot(), target, phase, weapons, program);
        var accepted = schedule(updated, plan); facts.addAll(accepted.emitted());
        return new RuleEngine.Local<>(accepted.state(), new WeaponHandling.Receipt(WeaponHandling.Outcome.ACCEPTED, Optional.of(plan)), facts);
    }
    private static RuleEngine.Local<EffectState> result(EffectState state, WeaponHandling.Outcome outcome) {
        return new RuleEngine.Local<>(state, new WeaponHandling.Receipt(outcome, Optional.empty()), List.of());
    }
    private static WeaponHandling.Plan plan(EffectState state, String holder, String token, Optional<String> targetSlot,
            Optional<Loadout.Gear> target, WeaponHandling.Phase phase, CompiledWeapons weapons, CompiledEffects program) {
        var loadout = state.equipment().getOrDefault(holder, Loadout.EMPTY);
        String slot = (phase == WeaponHandling.Phase.STOW ? loadout.drawn() : targetSlot).orElseThrow();
        var gear = loadout.slots().get(slot);
        String name = phase.name().toLowerCase(Locale.ROOT);
        var tags = new HashSet<>(weapons.itemTags(gear.definition())); tags.add("chorus:weapon_" + name);
        var origin = new com.imdomestic.chorus.effect.buff.BuffInstance.Origin(holder, gear.instance(), gear.instance(), "", tags);
        var query = new EffectEvent(holder, gear.instance(), origin, tags, Map.of(), Map.of(),
                Map.of("weapon", gear.instance(), "item", gear.definition(), "switch", token, "handling_phase", name));
        var definition = weapons.definition(gear.definition());
        var timing = definition == null ? Optional.<WeaponDefinition.Timing>empty() : definition.handling().map(h -> phase == WeaponHandling.Phase.STOW ? h.stow() : h.ready());
        Measure input = new Measure(0, Unit.SECOND); Optional<CalculationPipeline.Result> calculation = Optional.empty();
        if (timing.isPresent()) {
            var value = timing.orElseThrow();
            var event = new RuleEngine.Event(0, 0, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("chorus:internal/handling_query", query));
            var evaluation = program.evaluation(state, new RuleEngine.Context(event, "handling/" + token, new WeaponHandling.Scope(query), Map.of()), Map.of());
            input = value.value().evaluate(evaluation);
            if (!value.profiles().isEmpty()) calculation = Optional.of(program.calculatePipeline(state, holder, query, value.profiles(), input));
        }
        var duration = calculation.map(CalculationPipeline.Result::output).orElse(input);
        long micros = timing.isPresent() ? WeaponReload.micros(duration) : 0;
        long now = state.buffs().timeMicros();
        return new WeaponHandling.Plan(holder, token, targetSlot, target, slot, gear, origin, phase, now, Math.addExact(now, micros), input, duration, calculation, false);
    }
    private static RuleEngine.Local<EffectState> schedule(EffectState state, WeaponHandling.Plan plan) {
        var updated = state.withHandling(plan); var facts = new ArrayList<RuleEngine.Signal>();
        facts.add(fact("chorus:weapon_handling_started", plan, "accepted"));
        if (plan.dueAt() == plan.startedAt()) facts.add(new RuleEngine.Signal(WeaponHandling.DUE, plan));
        else updated = updated.schedule(plan.timer());
        return new RuleEngine.Local<>(updated, RuleEngine.Empty.INSTANCE, facts);
    }
    static boolean current(EffectState state, WeaponHandling.Plan plan) { return plan.equals(state.handling().get(plan.holder())); }
    static WeaponHandling.Apply prepare(EffectState state, WeaponHandling.Plan plan, CompiledEffects program) {
        if (!current(state, plan) || plan.completed() || plan.dueAt() != state.buffs().timeMicros()) throw new IllegalStateException("Handling is not at its accepted boundary");
        var change = plan.change(state.equipment().getOrDefault(plan.holder(), Loadout.EMPTY));
        // Validate the complete pure equipment transition before requesting a physical mutation.
        program.changeEquipment(clear(state, plan), change);
        return new WeaponHandling.Apply(plan, change);
    }
    static RuleEngine.Local<EffectState> finish(EffectState state, WeaponHandling.Plan plan, WeaponHandling.Applied result, CompiledEffects program) {
        if (!current(state, plan)) return new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, List.of());
        var expected = prepare(state, plan, program);
        if (!expected.equals(result.query())) throw new IllegalArgumentException("Handling receipt belongs to another phase");
        if (!result.applied()) return cancel(state, plan, "host_rejected");
        var equipment = program.changeEquipment(clear(state, plan), expected.change());
        var updated = equipment.state(); var facts = new ArrayList<>(equipment.emitted());
        facts.add(fact("chorus:weapon_handling_finished", plan, "completed"));
        if (plan.phase() == WeaponHandling.Phase.STOW) {
            var between = plan.between(); updated = updated.withHandling(between);
            facts.add(new RuleEngine.Signal(WeaponHandling.NEXT, between));
        } else facts.add(fact("chorus:weapon_switch_finished", plan, "completed"));
        return new RuleEngine.Local<>(updated, result, facts);
    }
    static RuleEngine.Local<EffectState> next(EffectState state, WeaponHandling.Plan previous, CompiledWeapons weapons, CompiledEffects program) {
        if (!current(state, previous)) return new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, List.of());
        if (!previous.completed() || previous.dueAt() != state.buffs().timeMicros()) throw new IllegalStateException("Not a committed stow boundary");
        if (previous.target().isEmpty()) return new RuleEngine.Local<>(clear(state, previous), RuleEngine.Empty.INSTANCE,
                List.of(fact("chorus:weapon_switch_finished", previous, "completed")));
        // Stow reactions have run; the new weapon samples its own current modifiers at this phase boundary.
        return schedule(clear(state, previous), plan(state, previous.holder(), previous.token(), previous.targetSlot(), previous.target(), WeaponHandling.Phase.READY, weapons, program));
    }
    static RuleEngine.Local<EffectState> reconcile(EffectState state, String holder) {
        var plan = state.handling().get(holder);
        return plan != null && !plan.matches(state.equipment().getOrDefault(holder, Loadout.EMPTY)) ? cancel(state, plan, "equipment_changed")
                : new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, List.of());
    }
    private static EffectState clear(EffectState state, WeaponHandling.Plan plan) { return state.withoutHandling(plan.holder()).cancel(plan.timerId()); }
    private static RuleEngine.Local<EffectState> cancel(EffectState state, WeaponHandling.Plan plan, String reason) {
        return new RuleEngine.Local<>(clear(state, plan), RuleEngine.Empty.INSTANCE, List.of(fact("chorus:weapon_switch_cancelled", plan, reason)));
    }
    static void validate(EffectState state) {
        for (var plan : state.handling().values()) {
            long now = state.buffs().timeMicros();
            if (plan.startedAt() > now || plan.dueAt() < now || !plan.matches(state.equipment().getOrDefault(plan.holder(), Loadout.EMPTY)))
                throw new IllegalArgumentException("Stale accepted handling phase");
            if (plan.completed()) {
                if (plan.dueAt() != now || state.timers().containsKey(plan.timerId())) throw new IllegalArgumentException("Invalid stow completion boundary");
            } else if (plan.dueAt() > now && !plan.timer().equals(state.timers().get(plan.timerId())))
                throw new IllegalArgumentException("Handling timer differs from its accepted phase");
        }
    }
    private static RuleEngine.Signal fact(String type, WeaponHandling.Plan plan, String reason) {
        var event = new EffectEvent(plan.holder(), plan.gear().instance(), plan.origin(), plan.origin().tags(),
                Map.of("duration", plan.duration(), "scheduled_duration", new Measure((plan.dueAt() - plan.startedAt()) / 1_000_000.0, Unit.SECOND)), Map.of(),
                Map.of("weapon", plan.gear().instance(), "item", plan.gear().definition(), "switch", plan.token(),
                        "handling_phase", plan.phase().name().toLowerCase(Locale.ROOT), "reason", reason));
        return new RuleEngine.Signal(type, new WeaponHandling.Fact(event, plan));
    }
}
