package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Prototype validation and pure reload transactions, independent of Minecraft input/rendering. */
final class CompiledWeapons {
    private final Map<String, WeaponDefinition> definitions = new TreeMap<>();
    private final Map<String, EquipmentSchema.Item> items = new TreeMap<>();
    private final CompiledEquipment equipment;
    CompiledWeapons(List<WeaponDefinition> weapons, CompiledEquipment equipment, Validation validation, Map<String, CalculationProfile> profiles) {
        this.equipment = equipment;
        equipment.schema().items().forEach(item -> items.put(item.id(), item));
        for (var weapon : weapons) {
            if (definitions.putIfAbsent(weapon.item(), weapon) != null) throw new IllegalArgumentException("Duplicate weapon definition: " + weapon.item());
            if (!items.containsKey(weapon.item())) throw new IllegalArgumentException("Weapon references unknown equipment item: " + weapon.item());
            weapon.ammunition().capacityProfile().ifPresent(validation::ammoProfile);
            var input = weapon.reload().value().unit(validation);
            if (weapon.reload().profile().isPresent()) {
                var profile = profiles.get(weapon.reload().profile().orElseThrow());
                if (profile == null) throw new IllegalArgumentException("Unknown reload profile");
                Validation.same(input, profile.inputUnit()); Validation.same(Unit.SECOND, profile.outputUnit());
            } else {
                Validation.same(input, Unit.SECOND);
                if (weapon.reload().value() instanceof Value.Constant c) WeaponReload.micros(new Measure(c.value(), input));
            }
        }
    }
    RuleEngine.Local<EffectState> equip(EffectState state, EquipmentChange change, CompiledEffects program) {
        // A weapon instance cannot borrow another holder's ammunition account while still equipped there.
        for (var other : state.equipment().entrySet()) if (!other.getKey().equals(change.holder()))
            for (var gear : change.after().slots().values()) if (other.getValue().slots().values().stream().anyMatch(g -> g.instance().equals(gear.instance())))
                throw new IllegalArgumentException("Equipment instance is already owned by another holder");
        var applied = change.apply(state, equipment); var updated = applied.state();
        var facts = new ArrayList<>(applied.emitted());
        for (var entry : change.after().slots().entrySet()) {
            var gear = entry.getValue(); var definition = definitions.get(gear.definition()); if (definition == null) continue;
            if (equipment.schema().slots().stream().noneMatch(slot -> slot.id().equals(entry.getKey()) && slot.weapon()))
                throw new IllegalArgumentException("Weapon behavior requires a weapon slot");
            var existing = updated.ammunition().get(gear.instance());
            updated = updated.withAmmo(existing == null ? definition.ammunition().initialize(change.holder(), gear.instance()) : definition.ammunition().adopt(change.holder(), existing));
        }
        // Resolve only after every account/source is present, so cross-weapon capacity queries see one atomic loadout.
        for (var gear : change.after().slots().values()) if (definitions.containsKey(gear.definition())) program.ammoCapacity(updated, gear.instance());
        var pending = updated.reloads().get(change.holder());
        if (pending != null && !WeaponReload.drawn(change.after()).filter(pending.gear()::equals).isPresent()) {
            updated = clear(updated, pending); facts.add(fact("chorus:reload_cancelled", pending, "equipment_changed", Map.of()));
        }
        return new RuleEngine.Local<>(updated, applied.result(), facts);
    }
    RuleEngine.Local<EffectState> begin(EffectState state, WeaponReload.Request request, CompiledEffects program) {
        var gear = WeaponReload.drawn(state.equipment().getOrDefault(request.holder(), Loadout.EMPTY));
        if (gear.isEmpty()) return reject(state, WeaponReload.Outcome.EMPTY_HANDS);
        var weapon = gear.orElseThrow(); var definition = definitions.get(weapon.definition());
        if (definition == null) return reject(state, WeaponReload.Outcome.NOT_CONFIGURED);
        if (state.reloads().containsKey(request.holder())) return reject(state, WeaponReload.Outcome.BUSY);
        var ammunition = program.ammoCapacity(state, weapon.instance());
        if (ammunition.read(AmmoState.Field.MISSING) == 0) return reject(state, WeaponReload.Outcome.FULL);
        if (ammunition.account().reserve().filter(reserve -> reserve.rounds() == 0).isPresent()) return reject(state, WeaponReload.Outcome.NO_RESERVES);
        var tags = new HashSet<>(items.get(weapon.definition()).tags()); tags.add("chorus:manual_reload");
        var origin = new BuffInstance.Origin(request.holder(), weapon.instance(), weapon.instance(), "", tags);
        var query = new EffectEvent(request.holder(), weapon.instance(), origin, tags, Map.of(), Map.of(), Map.of("weapon", weapon.instance(), "item", weapon.definition()));
        var event = new RuleEngine.Event(0, 0, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("chorus:internal/reload_query", query));
        var evaluation = program.evaluation(state, new RuleEngine.Context(event, "reload/" + request.token(), new WeaponReload.Scope(query), Map.of()), Map.of());
        var input = definition.reload().value().evaluate(evaluation);
        var calculation = definition.reload().profile().map(id -> program.calculate(state, request.holder(), query, id, input, List.of()));
        var duration = calculation.map(CalculationProfile.Result::output).orElse(input);
        long now = state.buffs().timeMicros(); long due = Math.addExact(now, WeaponReload.micros(duration));
        var plan = new WeaponReload.Plan(request.holder(), request.token(), weapon, origin, now, due, input, duration, calculation);
        var updated = state.withReload(plan).schedule(plan.timer());
        return new RuleEngine.Local<>(updated, new WeaponReload.Receipt(WeaponReload.Outcome.ACCEPTED, Optional.of(plan)),
                List.of(fact("chorus:reload_started", plan, "manual", Map.of())));
    }
    RuleEngine.Local<EffectState> finish(EffectState state, WeaponReload.Plan plan, WeaponReload.Verified verification, CompiledEffects program) {
        if (!verification.query().plan().equals(plan)) throw new IllegalArgumentException("Reload verification belongs to another request");
        if (!plan.equals(state.reloads().get(plan.holder()))) return new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, List.of());
        if (state.buffs().timeMicros() != plan.dueAt()) throw new IllegalArgumentException("Reload completion is not at its accepted deadline");
        var updated = clear(state, plan);
        if (!verification.allowed() || !WeaponReload.drawn(state.equipment().getOrDefault(plan.holder(), Loadout.EMPTY)).filter(plan.gear()::equals).isPresent())
            return new RuleEngine.Local<>(updated, RuleEngine.Empty.INSTANCE, List.of(fact("chorus:reload_cancelled", plan, "host_rejected", Map.of())));
        var view = program.ammoCapacity(updated, plan.gear().instance());
        var transfer = Ammunition.refill(view.account(), view.read(AmmoState.Field.MISSING), view.capacity());
        if (transfer.applied() == 0) return new RuleEngine.Local<>(updated, RuleEngine.Empty.INSTANCE, List.of(fact("chorus:reload_cancelled", plan, "no_ammunition_transferred", Map.of())));
        updated = updated.withAmmo(transfer.after());
        var ammoFact = AmmoFacts.changed(plan.holder(), plan.origin(), transfer, view.capacity());
        return new RuleEngine.Local<>(updated, new AmmoActions.Change(transfer, view.after(transfer.after())), List.of(
                new RuleEngine.Signal("chorus:ammo_refilled", ammoFact), new RuleEngine.Signal("chorus:ammo_changed", ammoFact),
                fact("chorus:reload_finished", plan, "manual", ammoFact.numbers())));
    }
    void validate(EffectState state) {
        for (var plan : state.reloads().values()) {
            if (!definitions.containsKey(plan.gear().definition()) || plan.startedAt() > state.buffs().timeMicros() || plan.dueAt() < state.buffs().timeMicros())
                throw new IllegalArgumentException("Unknown or stale accepted reload");
            if (plan.dueAt() > state.buffs().timeMicros() && !plan.timer().equals(state.timers().get(plan.timerId()))) throw new IllegalArgumentException("Reload timer differs from its accepted plan");
        }
    }
    private static EffectState clear(EffectState state, WeaponReload.Plan plan) { return state.withoutReload(plan.holder()).cancel(plan.timerId()); }
    private static RuleEngine.Local<EffectState> reject(EffectState state, WeaponReload.Outcome outcome) {
        return new RuleEngine.Local<>(state, new WeaponReload.Receipt(outcome, Optional.empty()), List.of());
    }
    private static RuleEngine.Signal fact(String type, WeaponReload.Plan plan, String reason, Map<String, Measure> ammo) {
        var numbers = new HashMap<>(ammo); numbers.put("duration", plan.duration());
        numbers.put("scheduled_duration", new Measure((plan.dueAt() - plan.startedAt()) / 1_000_000.0, Unit.SECOND));
        var event = new EffectEvent(plan.holder(), plan.gear().instance(), plan.origin(), plan.origin().tags(), numbers,
                Map.of("manual", true), Map.of("weapon", plan.gear().instance(), "item", plan.gear().definition(), "reload", plan.token(), "reason", reason));
        return new RuleEngine.Signal(type, event);
    }
}
