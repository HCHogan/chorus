package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/** Domain state for the effect timeline. World references are identifiers, never mutable entities. */
public record EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots) {
    public enum Mode { PVE, PVP }
    public record Lifetime(BuffInstance.Key key, long generation) {
        public Lifetime { Objects.requireNonNull(key); if (generation < 1) throw new IllegalArgumentException("Invalid buff generation"); }
        public boolean active(BuffStore store) { return store.active(key).filter(value -> value.generation() == generation).isPresent(); }
    }
    public record Timer(String id, long dueAt, long intervalMicros, int remaining, RuleEngine.Signal signal, Optional<Lifetime> lifetime,
            OptionalLong pausedRemaining) {
        public Timer {
            Objects.requireNonNull(id); Objects.requireNonNull(signal); Objects.requireNonNull(lifetime);
            Objects.requireNonNull(pausedRemaining);
            if (id.isBlank() || dueAt < 0 || remaining == 0 || remaining < -1 || intervalMicros < 0
                    || (remaining != 1 && intervalMicros == 0)) throw new IllegalArgumentException("Invalid scheduled fact");
            if (pausedRemaining.isPresent() ? lifetime.isEmpty() || dueAt != Long.MAX_VALUE || pausedRemaining.getAsLong() <= 0
                    : dueAt == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid paused timer");
            if (signal.payload() instanceof EffectTimers.Scheduled scheduled
                    && (!scheduled.owner().key(scheduled.name()).equals(id) || !scheduled.owner().buff().equals(lifetime))) {
                throw new IllegalArgumentException("Scheduled ownership differs from timer identity/lifetime");
            }
            if (signal.payload() instanceof EffectContinuations.Pending pending
                    && (!signal.type().equals(EffectContinuations.EVENT) || !pending.id().equals(id)
                    || !pending.owner().flatMap(EffectTimers.Owner::buff).equals(lifetime) || remaining != 1 || intervalMicros != 0)) {
                throw new IllegalArgumentException("Continuation differs from its one-shot timer identity/lifetime");
            }
            if (signal.payload() instanceof com.imdomestic.chorus.effect.weapon.WeaponReload.Plan plan
                    && (!signal.type().equals(com.imdomestic.chorus.effect.weapon.WeaponReload.DUE) || !plan.timerId().equals(id)
                    || dueAt != plan.dueAt() || remaining != 1 || intervalMicros != 0 || lifetime.isPresent() || pausedRemaining.isPresent()))
                throw new IllegalArgumentException("Reload differs from its one-shot timer");
        }
        public Timer(String id, long dueAt, long intervalMicros, int remaining, RuleEngine.Signal signal, Optional<Lifetime> lifetime) {
            this(id, dueAt, intervalMicros, remaining, signal, lifetime, OptionalLong.empty());
        }
    }
    public EffectState {
        Objects.requireNonNull(buffs);
        Objects.requireNonNull(mode);
        shots = Collections.unmodifiableMap(new TreeMap<>(shots));
        for (var entry : shots.entrySet()) if (!entry.getKey().equals(entry.getValue().gear().instance()) || entry.getValue().firedAt() > buffs.timeMicros())
            throw new IllegalArgumentException("Wrong accepted shot identity or clock");
        reloads = Collections.unmodifiableMap(new TreeMap<>(reloads));
        for (var entry : reloads.entrySet()) if (!entry.getKey().equals(entry.getValue().holder())) throw new IllegalArgumentException("Wrong reload holder identity");
        ammunition = Collections.unmodifiableMap(new TreeMap<>(ammunition));
        for (var entry : ammunition.entrySet()) if (!entry.getKey().equals(entry.getValue().weapon())) throw new IllegalArgumentException("Wrong ammunition weapon identity");
        abilities = Collections.unmodifiableMap(new TreeMap<>(abilities));
        if (abilities.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Missing ability holder");
        equipment = Collections.unmodifiableMap(new TreeMap<>(equipment));
        if (equipment.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Missing equipment holder");
        sources = Collections.unmodifiableMap(new TreeMap<>(sources));
        for (var entry : sources.entrySet()) if (!entry.getKey().equals(entry.getValue().instance())) throw new IllegalArgumentException("Wrong source identity");
        resources = Map.copyOf(resources);
        for (var entry : resources.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().key()) || entry.getValue().timeMicros() != buffs.timeMicros()) {
                throw new IllegalArgumentException("Resource identity or clock differs from the effect state");
            }
        }
        timers = Collections.unmodifiableMap(new TreeMap<>(timers));
        for (var entry : timers.entrySet()) {
            var timer = entry.getValue();
            if (!entry.getKey().equals(timer.id()) || timer.dueAt() <= buffs.timeMicros()) {
                throw new IllegalArgumentException("Timer must be scheduled after the current boundary");
            }
            if (timer.lifetime().filter(lifetime -> lifetime.active(buffs)).isPresent()
                    && buffs.active(timer.lifetime().orElseThrow().key()).orElseThrow().pausedAt().isPresent() != timer.pausedRemaining().isPresent()) {
                throw new IllegalArgumentException("Timer pause state differs from its bound buff");
            }
        }
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers, Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers, Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers, Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers, Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment) {
        this(buffs, resources, timers, sources, mode, equipment, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers, Map<String, EffectSource> sources, Mode mode) {
        this(buffs, resources, timers, sources, mode, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers) {
        this(buffs, resources, timers, Map.of(), Mode.PVE);
    }
    public static EffectState empty() { return new EffectState(BuffStore.empty(), Map.of(), Map.of()); }
    public EffectState withBuffs(BuffStore value) {
        var updated = new TreeMap<>(timers);
        for (var timer : timers.values()) if (timer.lifetime().isPresent()) {
            var lifetime = timer.lifetime().orElseThrow();
            if (!lifetime.active(value)) { updated.remove(timer.id()); continue; }
            boolean paused = value.active(lifetime.key()).orElseThrow().pausedAt().isPresent();
            if (paused && timer.pausedRemaining().isEmpty()) {
                updated.put(timer.id(), new Timer(timer.id(), Long.MAX_VALUE, timer.intervalMicros(), timer.remaining(), timer.signal(),
                        timer.lifetime(), OptionalLong.of(Math.subtractExact(timer.dueAt(), value.timeMicros()))));
            } else if (!paused && timer.pausedRemaining().isPresent()) {
                updated.put(timer.id(), new Timer(timer.id(), Math.addExact(value.timeMicros(), timer.pausedRemaining().getAsLong()),
                        timer.intervalMicros(), timer.remaining(), timer.signal(), timer.lifetime()));
            }
        }
        return new EffectState(value, resources, updated, sources, mode, equipment, abilities, ammunition, reloads, shots);
    }
    public EffectState withShot(com.imdomestic.chorus.effect.weapon.WeaponFire.Shot value) {
        var copy = new TreeMap<>(shots); copy.put(value.gear().instance(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, copy);
    }
    public EffectState withReload(com.imdomestic.chorus.effect.weapon.WeaponReload.Plan value) {
        var copy = new TreeMap<>(reloads); copy.put(value.holder(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, copy, shots);
    }
    public EffectState withoutReload(String holder) {
        var copy = new TreeMap<>(reloads); copy.remove(holder);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, copy, shots);
    }
    public EffectState withAmmo(com.imdomestic.chorus.effect.ammo.AmmoState value) {
        var copy = new TreeMap<>(ammunition); copy.put(value.weapon(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, copy, reloads, shots);
    }
    public EffectState withResource(ResourceState value) {
        var copy = new HashMap<>(resources); copy.put(value.key(), value); return new EffectState(buffs, copy, timers, sources, mode, equipment, abilities, ammunition, reloads, shots);
    }
    public EffectState schedule(Timer timer) {
        var copy = new TreeMap<>(timers);
        if (copy.putIfAbsent(timer.id(), timer) != null) throw new IllegalArgumentException("Duplicate timer identity: " + timer.id());
        return new EffectState(buffs, resources, copy, sources, mode, equipment, abilities, ammunition, reloads, shots);
    }
    public EffectState cancel(String id) { var copy = new TreeMap<>(timers); copy.remove(id); return new EffectState(buffs, resources, copy, sources, mode, equipment, abilities, ammunition, reloads, shots); }
    public EffectState withSource(EffectSource source) {
        var copy = new TreeMap<>(sources); copy.put(source.instance(), source); return withSources(copy);
    }
    public EffectState withoutSource(String instance) {
        var copy = new TreeMap<>(sources); copy.remove(instance); return withSources(copy);
    }
    public EffectState withSources(Map<String, EffectSource> value) {
        var updated = new TreeMap<>(timers); updated.values().removeIf(timer -> !EffectTimers.active(timer, value, buffs));
        return new EffectState(buffs, resources, updated, value, mode, equipment, abilities, ammunition, reloads, shots);
    }
    public EffectState withEquipment(String holder, com.imdomestic.chorus.effect.equipment.Loadout value) {
        var updated = new TreeMap<>(equipment);
        if (value.equals(com.imdomestic.chorus.effect.equipment.Loadout.EMPTY)) updated.remove(holder); else updated.put(holder, value);
        return new EffectState(buffs, resources, timers, sources, mode, updated, abilities, ammunition, reloads, shots);
    }
    public EffectState withAbilities(String holder, com.imdomestic.chorus.effect.ability.AbilityLoadout value) {
        var updated = new TreeMap<>(abilities);
        if (value.equals(com.imdomestic.chorus.effect.ability.AbilityLoadout.EMPTY)) updated.remove(holder); else updated.put(holder, value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, updated, ammunition, reloads, shots);
    }
    public EffectState withMode(Mode value) { return new EffectState(buffs, resources, timers, sources, value, equipment, abilities, ammunition, reloads, shots); }
}
