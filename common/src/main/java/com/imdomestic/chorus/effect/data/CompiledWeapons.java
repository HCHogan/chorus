package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Prototype validation and pure reload/fire transactions, independent of Minecraft input/rendering. */
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
            weapon.fire().ifPresent(fire -> {
                fire.condition().validate(validation); Validation.same(fire.cost().unit(validation), Unit.ROUND);
                if (fire.cost() instanceof Value.Constant c) WeaponFire.rounds(new Measure(c.value(), Unit.ROUND));
                var unit = fire.interval().unit(validation);
                if (fire.intervalProfile().isPresent()) {
                    var profile = profiles.get(fire.intervalProfile().orElseThrow());
                    if (profile == null) throw new IllegalArgumentException("Unknown fire interval profile");
                    Validation.same(unit, profile.inputUnit()); Validation.same(profile.outputUnit(), Unit.SECOND);
                } else {
                    Validation.same(unit, Unit.SECOND);
                    if (fire.interval() instanceof Value.Constant c) WeaponFire.micros(new Measure(c.value(), unit));
                }
            });
            validateTiming(weapon.reload().value(), weapon.reload().profiles(), validation, profiles);
            weapon.reload().insert().ifPresent(insert -> {
                validateTiming(insert.repeat().value(), insert.repeat().profiles(), validation, profiles);
                var unit = insert.rounds().unit(validation);
                if (insert.roundsProfile().isPresent()) {
                    var profile = profiles.get(insert.roundsProfile().orElseThrow());
                    if (profile == null) throw new IllegalArgumentException("Unknown insertion rounds profile");
                    Validation.same(unit, profile.inputUnit()); Validation.same(Unit.ROUND, profile.outputUnit());
                } else {
                    Validation.same(unit, Unit.ROUND);
                    if (insert.rounds() instanceof Value.Constant c) WeaponReload.rounds(new Measure(c.value(), unit));
                }
            });
        }
    }
    private static void validateTiming(Value value, List<String> ids, Validation validation, Map<String, CalculationProfile> profiles) {
        var input = value.unit(validation);
        if (!ids.isEmpty()) {
            var pipeline = CalculationPipeline.resolve(ids, profiles);
            Validation.same(input, pipeline.inputUnit()); Validation.same(Unit.SECOND, pipeline.outputUnit());
        } else {
            Validation.same(input, Unit.SECOND);
            if (value instanceof Value.Constant c) WeaponReload.micros(new Measure(c.value(), input));
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
    private List<Loadout.Gear> instantWeapons(Loadout loadout, InstantReload.Selection selection) {
        return loadout.slots().entrySet().stream()
                .filter(entry -> selection == InstantReload.Selection.EQUIPPED || loadout.drawn().filter(entry.getKey()::equals).isPresent())
                .map(Map.Entry::getValue).filter(gear -> definitions.containsKey(gear.definition())).toList();
    }
    Optional<InstantReload.Check> instant(EffectState state, String holder, InstantReload.Selection selection,
            InstantReload.Completion completion, String reason, BuffInstance.Origin cause, RuleEngine.OperationId operation) {
        var loadout = state.equipment().getOrDefault(holder, Loadout.EMPTY);
        if (instantWeapons(loadout, selection).isEmpty()) return Optional.empty();
        return Optional.of(new InstantReload.Check(holder, loadout, selection, completion, reason, cause, operation));
    }
    RuleEngine.Local<EffectState> instant(EffectState state, InstantReload.Checked checked, CompiledEffects program) {
        var request = checked.query();
        if (!checked.allowed()) return instantRejected(state, InstantReload.Outcome.REJECTED);
        if (!request.equipment().equals(state.equipment().getOrDefault(request.holder(), Loadout.EMPTY)))
            return instantRejected(state, InstantReload.Outcome.STALE_EQUIPMENT);
        var candidates = instantWeapons(request.equipment(), request.selection());
        if (candidates.isEmpty()) return instantRejected(state, InstantReload.Outcome.EMPTY);
        var transfers = new ArrayList<InstantReload.Transfer>();
        // Resolve every capacity against the same pre-commit state; no weapon sees a partly reloaded loadout.
        for (var gear : candidates) {
            var view = program.ammoCapacity(state, gear.instance());
            var mutation = Ammunition.refill(view.account(), view.read(AmmoState.Field.MISSING), view.capacity());
            var tags = new HashSet<>(items.get(gear.definition()).tags()); tags.add("chorus:instant_reload");
            var origin = new BuffInstance.Origin(request.holder(), request.cause().source(), gear.instance(), request.cause().ability(), tags);
            transfers.add(new InstantReload.Transfer(gear, mutation, view.after(mutation.after()), origin,
                    mutation.applied() > 0 || request.completion() == InstantReload.Completion.VERIFIED));
        }
        var updated = state; var facts = new ArrayList<RuleEngine.Signal>();
        var manual = state.reloads().get(request.holder());
        if (manual != null && candidates.stream().anyMatch(gear -> gear.instance().equals(manual.gear().instance()))) {
            updated = clear(updated, manual); facts.add(fact("chorus:reload_cancelled", manual, "instant_reload", Map.of()));
        }
        for (var transfer : transfers) updated = updated.withAmmo(transfer.mutation().after());
        for (var transfer : transfers) {
            var ammo = AmmoFacts.changed(request.holder(), transfer.origin(), transfer.mutation(), transfer.view().capacity());
            if (transfer.mutation().changed()) {
                facts.add(new RuleEngine.Signal("chorus:ammo_refilled", ammo)); facts.add(new RuleEngine.Signal("chorus:ammo_changed", ammo));
            }
            if (transfer.completed()) {
                var numbers = new HashMap<>(ammo.numbers()); numbers.put("duration", new Measure(0, Unit.SECOND)); numbers.put("scheduled_duration", new Measure(0, Unit.SECOND));
                numbers.put("reload_step", new Measure(0, Unit.COUNT)); numbers.put("planned_rounds", new Measure(transfer.mutation().requested(), Unit.ROUND));
                var event = new EffectEvent(request.holder(), transfer.gear().instance(), transfer.origin(), transfer.origin().tags(), numbers,
                        Map.of("manual", false, "instant", true, "incremental", false), Map.of("weapon", transfer.gear().instance(), "item", transfer.gear().definition(),
                                "reason", request.reason(), "reload", request.token(), "cause_owner", request.cause().owner(), "cause_source", request.cause().source(), "cause_weapon", request.cause().weapon(), "cause_ability", request.cause().ability()));
                facts.add(new RuleEngine.Signal("chorus:reload_finished", new InstantReload.Completed(event, request, transfer)));
            }
        }
        return new RuleEngine.Local<>(updated, new InstantReload.Result(InstantReload.Outcome.VERIFIED, transfers), facts);
    }
    private static RuleEngine.Local<EffectState> instantRejected(EffectState state, InstantReload.Outcome outcome) {
        return new RuleEngine.Local<>(state, new InstantReload.Result(outcome, List.of()), List.of());
    }
    RuleEngine.Local<EffectState> begin(EffectState state, WeaponReload.Request request, CompiledEffects program) {
        var gear = WeaponReload.drawn(state.equipment().getOrDefault(request.holder(), Loadout.EMPTY));
        if (gear.isEmpty()) return reject(state, WeaponReload.Outcome.EMPTY_HANDS);
        var weapon = gear.orElseThrow(); var definition = definitions.get(weapon.definition());
        if (definition == null) return reject(state, WeaponReload.Outcome.NOT_CONFIGURED);
        var restriction=program.checkAction(state,ActionGate.Kind.WEAPON_RELOAD,ActionGate.Phase.START,reloadQuery(request.holder(),request.token(),weapon,0));
        if(!restriction.allowed())return new RuleEngine.Local<>(state,new WeaponReload.Receipt(WeaponReload.Outcome.RESTRICTED,Optional.empty(),Optional.of(restriction)),List.of());
        if (state.reloads().containsKey(request.holder())) return reject(state, WeaponReload.Outcome.BUSY);
        var ammunition = program.ammoCapacity(state, weapon.instance());
        if (ammunition.read(AmmoState.Field.MISSING) == 0) return reject(state, WeaponReload.Outcome.FULL);
        if (ammunition.account().reserve().filter(reserve -> reserve.rounds() == 0).isPresent()) return reject(state, WeaponReload.Outcome.NO_RESERVES);
        var plan = plan(state, request.holder(), request.token(), weapon, 0, program);
        var updated = state.withReload(plan).schedule(plan.timer());
        return new RuleEngine.Local<>(updated, new WeaponReload.Receipt(WeaponReload.Outcome.ACCEPTED, Optional.of(plan)),
                List.of(fact("chorus:reload_started", plan, "manual", Map.of())));
    }
    private EffectEvent reloadQuery(String holder,String token,Loadout.Gear weapon,int step){
        var reload = definitions.get(weapon.definition()).reload();
        var tags = new HashSet<>(items.get(weapon.definition()).tags()); tags.add("chorus:manual_reload");
        var origin = new BuffInstance.Origin(holder, weapon.instance(), weapon.instance(), "", tags);
        return new EffectEvent(holder, weapon.instance(), origin, tags, Map.of("reload_step", new Measure(step, Unit.COUNT)),
                Map.of("incremental", reload.insert().isPresent()), Map.of("weapon", weapon.instance(), "item", weapon.definition(), "reload", token));
    }
    private WeaponReload.Plan plan(EffectState state, String holder, String token, Loadout.Gear weapon, int step, CompiledEffects program) {
        var reload = definitions.get(weapon.definition()).reload();
        var timing = step == 0 ? new WeaponDefinition.Timing(reload.value(), reload.profiles()) : reload.insert().orElseThrow().repeat();
        var query=reloadQuery(holder,token,weapon,step);var origin=query.source();
        var event = new RuleEngine.Event(0, 0, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("chorus:internal/reload_query", query));
        var evaluation = program.evaluation(state, new RuleEngine.Context(event, "reload/" + token, new WeaponReload.Scope(query), Map.of()), Map.of());
        var input = timing.value().evaluate(evaluation);
        Optional<CalculationPipeline.Result> calculation = timing.profiles().isEmpty() ? Optional.empty()
                : Optional.of(program.calculatePipeline(state, holder, query, timing.profiles(), input));
        var duration = calculation.map(CalculationPipeline.Result::output).orElse(input);
        var portion = reload.insert().map(insert -> {
            var base = insert.rounds().evaluate(evaluation);
            var result = insert.roundsProfile().map(id -> program.calculate(state, holder, query, id, base, List.of()));
            return new WeaponReload.Portion(base, WeaponReload.rounds(result.map(CalculationProfile.Result::output).orElse(base)), result);
        });
        long now = state.buffs().timeMicros(); long due = Math.addExact(now, WeaponReload.micros(duration));
        return new WeaponReload.Plan(holder, token, weapon, origin, now, due, input, duration, calculation, portion, step, WeaponReload.Phase.WAITING);
    }
    RuleEngine.Local<EffectState> next(EffectState state, WeaponReload.Plan previous, CompiledEffects program) {
        if (!previous.equals(state.reloads().get(previous.holder()))) return new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, List.of());
        if (previous.phase() != WeaponReload.Phase.BETWEEN_INSERTS || previous.dueAt() != state.buffs().timeMicros())
            throw new IllegalArgumentException("Reload continuation is not at the committed insertion boundary");
        var view = program.ammoCapacity(state, previous.gear().instance());
        if (view.read(AmmoState.Field.MISSING) == 0 || view.account().reserve().filter(r -> r.rounds() == 0).isPresent())
            return new RuleEngine.Local<>(clear(state, previous), RuleEngine.Empty.INSTANCE,
                    List.of(fact("chorus:reload_ended", previous, view.read(AmmoState.Field.MISSING) == 0 ? "full" : "no_reserves", Map.of())));
        var restriction=program.checkAction(state,ActionGate.Kind.WEAPON_RELOAD,ActionGate.Phase.CONTINUE,reloadQuery(previous.holder(),previous.token(),previous.gear(),Math.incrementExact(previous.step())));
        if(!restriction.allowed())return restrictedReload(state,previous,restriction);
        // Queueing this after completion facts lets their committed reactions affect the next insertion.
        // A failed calculation retains the previous ammo transfer and this explicit between-insertions marker.
        var plan = plan(state, previous.holder(), previous.token(), previous.gear(), Math.incrementExact(previous.step()), program);
        return new RuleEngine.Local<>(state.withReload(plan).schedule(plan.timer()), RuleEngine.Empty.INSTANCE,
                List.of(fact("chorus:reload_continued", plan, "manual", Map.of())));
    }

    RuleEngine.Local<EffectState> finish(EffectState state, WeaponReload.Plan plan, WeaponReload.Verified verification, CompiledEffects program) {
        if (!verification.query().plan().equals(plan)) throw new IllegalArgumentException("Reload verification belongs to another request");
        if (!plan.equals(state.reloads().get(plan.holder()))) return new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, List.of());
        if (plan.phase() != WeaponReload.Phase.WAITING || state.buffs().timeMicros() != plan.dueAt()) throw new IllegalArgumentException("Reload completion is not at its accepted deadline");
        var updated = clear(state, plan);
        if (!verification.allowed() || !WeaponReload.drawn(state.equipment().getOrDefault(plan.holder(), Loadout.EMPTY)).filter(plan.gear()::equals).isPresent())
            return new RuleEngine.Local<>(updated, RuleEngine.Empty.INSTANCE, List.of(fact("chorus:reload_cancelled", plan, "host_rejected", Map.of())));
        var restriction=program.checkAction(state,ActionGate.Kind.WEAPON_RELOAD,ActionGate.Phase.COMPLETE,reloadQuery(plan.holder(),plan.token(),plan.gear(),plan.step()));
        if(!restriction.allowed())return restrictedReload(state,plan,restriction);
        var view = program.ammoCapacity(updated, plan.gear().instance());
        var transfer = Ammunition.refill(view.account(), plan.portion().map(WeaponReload.Portion::rounds).orElseGet(() -> view.read(AmmoState.Field.MISSING)), view.capacity());
        if (transfer.applied() == 0) return new RuleEngine.Local<>(updated, RuleEngine.Empty.INSTANCE, List.of(fact("chorus:reload_cancelled", plan, "no_ammunition_transferred", Map.of())));
        updated = updated.withAmmo(transfer.after());
        var ammoFact = AmmoFacts.changed(plan.holder(), plan.origin(), transfer, view.capacity());
        var facts = new ArrayList<>(List.of(new RuleEngine.Signal("chorus:ammo_refilled", ammoFact), new RuleEngine.Signal("chorus:ammo_changed", ammoFact),
                fact("chorus:reload_finished", plan, "manual", ammoFact.numbers())));
        if (plan.portion().isPresent()) {
            var between = plan.between(); updated = updated.withReload(between);
            facts.add(new RuleEngine.Signal(WeaponReload.NEXT, between));
        }
        return new RuleEngine.Local<>(updated, new AmmoActions.Change(transfer, view.after(transfer.after())), facts);
    }
    WeaponDefinition definition(String item) { return definitions.get(item); }
    RuleEngine.Local<EffectState> fire(EffectState state, WeaponFire.Request request, CompiledEffects program) {
        var gear = WeaponReload.drawn(state.equipment().getOrDefault(request.holder(), Loadout.EMPTY));
        if (gear.isEmpty()) return rejectFire(state, WeaponFire.Outcome.EMPTY_HANDS);
        var weapon = gear.orElseThrow(); var definition = definitions.get(weapon.definition());
        if (definition == null || definition.fire().isEmpty()) return rejectFire(state, WeaponFire.Outcome.NOT_CONFIGURED);
        var fire = definition.fire().orElseThrow(); long now = state.buffs().timeMicros();
        var previous = state.shots().get(weapon.instance());
        if (previous != null && now < previous.readyAt()) return rejectFire(state, WeaponFire.Outcome.COOLDOWN);
        var tags = new HashSet<>(items.get(weapon.definition()).tags()); tags.addAll(fire.tags());
        var origin = new BuffInstance.Origin(request.holder(), "shot/" + request.token(), weapon.instance(), "", tags);
        var query = new EffectEvent(request.holder(), "", origin, tags, Map.of(), Map.of(), Map.of("weapon", weapon.instance(), "item", weapon.definition(), "shot", request.token()));
        var restriction=program.checkAction(state,ActionGate.Kind.WEAPON_FIRE,ActionGate.Phase.START,query);
        if(!restriction.allowed())return new RuleEngine.Local<>(state,new WeaponFire.Receipt(WeaponFire.Outcome.RESTRICTED,Optional.empty(),Optional.of(restriction)),List.of());
        var event = new RuleEngine.Event(0, 0, Optional.empty(), now, new RuleEngine.Signal("chorus:internal/fire_query", query));
        var evaluation = program.evaluation(state, new RuleEngine.Context(event, origin.source(), new WeaponFire.Scope(query), Map.of()), Map.of());
        if (!fire.condition().test(evaluation)) return rejectFire(state, WeaponFire.Outcome.CONDITION);
        int rounds = WeaponFire.rounds(fire.cost().evaluate(evaluation));
        var view = program.ammoCapacity(state, weapon.instance());
        var cost = Ammunition.spend(view.account(), Ammunition.Pool.MAGAZINE, rounds);
        if (!cost.complete()) return rejectFire(state, WeaponFire.Outcome.NO_AMMUNITION);
        var input = fire.interval().evaluate(evaluation);
        var calculation = fire.intervalProfile().map(id -> program.calculate(state, request.holder(), query, id, input, List.of()));
        var interval = calculation.map(CalculationProfile.Result::output).orElse(input);
        var shot = new WeaponFire.Shot(request.holder(), request.token(), weapon, origin, now, Math.addExact(now, WeaponFire.micros(interval)), input, interval, calculation, cost);
        var updated = state.withAmmo(cost.after()).withShot(shot); var facts = new ArrayList<RuleEngine.Signal>();
        var reload = updated.reloads().get(request.holder());
        if (reload != null) { updated = clear(updated, reload); facts.add(fact("chorus:reload_cancelled", reload, "fire_accepted", Map.of())); }
        if (cost.changed()) {
            var changed = AmmoFacts.changed(request.holder(), origin, cost, view.capacity());
            facts.add(new RuleEngine.Signal("chorus:ammo_spent", changed)); facts.add(new RuleEngine.Signal("chorus:ammo_changed", changed));
        }
        var accepted = new EffectEvent(query.actor(), query.victim(), origin, tags,
                Map.of("cost", new Measure(rounds, Unit.ROUND), "interval", interval, "scheduled_interval", new Measure((shot.readyAt() - now) / 1_000_000.0, Unit.SECOND)), Map.of(), query.references());
        facts.add(new RuleEngine.Signal(WeaponFire.ACCEPTED, new WeaponFire.Accepted(accepted, definition, shot)));
        return new RuleEngine.Local<>(updated, new WeaponFire.Receipt(WeaponFire.Outcome.ACCEPTED, Optional.of(shot)), facts);
    }
    private static RuleEngine.Local<EffectState> rejectFire(EffectState state, WeaponFire.Outcome outcome) {
        return new RuleEngine.Local<>(state, new WeaponFire.Receipt(outcome, Optional.empty()), List.of());
    }
    void validate(EffectState state) {
        for (var shot : state.shots().values()) if (!definitions.containsKey(shot.gear().definition()) || !state.ammunition().containsKey(shot.gear().instance()))
            throw new IllegalArgumentException("Unknown accepted weapon fire");
        for (var plan : state.reloads().values()) {
            if (!definitions.containsKey(plan.gear().definition()) || plan.startedAt() > state.buffs().timeMicros() || plan.dueAt() < state.buffs().timeMicros())
                throw new IllegalArgumentException("Unknown or stale accepted reload");
            if (plan.portion().isPresent() != definitions.get(plan.gear().definition()).reload().insert().isPresent()) throw new IllegalArgumentException("Reload mode differs from its weapon definition");
            if (plan.phase() == WeaponReload.Phase.BETWEEN_INSERTS) {
                if (plan.dueAt() != state.buffs().timeMicros() || state.timers().containsKey(plan.timerId())) throw new IllegalArgumentException("Invalid committed insertion boundary");
            } else if (plan.dueAt() > state.buffs().timeMicros() && !plan.timer().equals(state.timers().get(plan.timerId()))) throw new IllegalArgumentException("Reload timer differs from its accepted plan");
        }
    }
    private static EffectState clear(EffectState state, WeaponReload.Plan plan) { return state.withoutReload(plan.holder()).cancel(plan.timerId()); }
    private static RuleEngine.Local<EffectState> restrictedReload(EffectState state,WeaponReload.Plan plan,ActionGate.Decision decision){
        var cancelled=fact("chorus:reload_cancelled",plan,"action_restricted",Map.of());
        return new RuleEngine.Local<>(clear(state,plan),decision,List.of(new RuleEngine.Signal(cancelled.type(),new ActionGate.Cancelled((EffectEvent)cancelled.payload(),decision))));
    }
    private static RuleEngine.Local<EffectState> reject(EffectState state, WeaponReload.Outcome outcome) {
        return new RuleEngine.Local<>(state, new WeaponReload.Receipt(outcome, Optional.empty()), List.of());
    }
    private static RuleEngine.Signal fact(String type, WeaponReload.Plan plan, String reason, Map<String, Measure> ammo) {
        var numbers = new HashMap<>(ammo); numbers.put("duration", plan.duration()); numbers.put("reload_step", new Measure(plan.step(), Unit.COUNT));
        plan.portion().ifPresent(portion -> numbers.put("planned_rounds", new Measure(portion.rounds(), Unit.ROUND)));
        numbers.put("scheduled_duration", new Measure((plan.dueAt() - plan.startedAt()) / 1_000_000.0, Unit.SECOND));
        var event = new EffectEvent(plan.holder(), plan.gear().instance(), plan.origin(), plan.origin().tags(), numbers,
                Map.of("manual", true, "incremental", plan.portion().isPresent()), Map.of("weapon", plan.gear().instance(), "item", plan.gear().definition(), "reload", plan.token(), "reason", reason));
        return new RuleEngine.Signal(type, event);
    }
}
