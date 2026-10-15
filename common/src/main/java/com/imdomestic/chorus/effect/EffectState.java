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
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups, Map<String, com.imdomestic.chorus.effect.resource.RetainedCosts.Entry> retainedCosts, Map<String, com.imdomestic.chorus.effect.combat.DamageTallies.Entry> damageTallies, Map<String, com.imdomestic.chorus.effect.weapon.WeaponHandling.Plan> handling, Map<String, com.imdomestic.chorus.effect.weapon.WeaponAim.Plan> aiming, Map<String, com.imdomestic.chorus.effect.target.EntityAttachments.Entry> attachments, Map<String, com.imdomestic.chorus.effect.weapon.WeaponCharge.Plan> charging, Map<String, com.imdomestic.chorus.effect.actor.ActorBinding> actors) {
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
                    && (plan.phase() != com.imdomestic.chorus.effect.weapon.WeaponReload.Phase.WAITING || !signal.type().equals(com.imdomestic.chorus.effect.weapon.WeaponReload.DUE) || !plan.timerId().equals(id)
                    || dueAt != plan.dueTime() || remaining != 1 || intervalMicros != 0 || lifetime.isPresent() || pausedRemaining.isPresent()))
                throw new IllegalArgumentException("Reload differs from its one-shot timer");
            if (signal.payload() instanceof com.imdomestic.chorus.effect.weapon.WeaponHandling.Plan plan
                    && (plan.completed() || !signal.type().equals(com.imdomestic.chorus.effect.weapon.WeaponHandling.DUE)
                    || !plan.timerId().equals(id) || dueAt != plan.dueTime() || dueAt <= plan.startTime()
                    || remaining != 1 || intervalMicros != 0 || lifetime.isPresent() || pausedRemaining.isPresent()))
                throw new IllegalArgumentException("Handling differs from its one-shot timer");
            if (signal.payload() instanceof com.imdomestic.chorus.effect.weapon.WeaponAim.Plan plan
                    && (plan.phase() == com.imdomestic.chorus.effect.weapon.WeaponAim.Phase.AIMED || !signal.type().equals(com.imdomestic.chorus.effect.weapon.WeaponAim.DUE)
                    || !plan.timerId().equals(id) || dueAt != plan.dueTime() || dueAt <= plan.startTime()
                    || remaining != 1 || intervalMicros != 0 || lifetime.isPresent() || pausedRemaining.isPresent()))
                throw new IllegalArgumentException("Aim differs from its one-shot timer");
            if (signal.payload() instanceof com.imdomestic.chorus.effect.finisher.Finisher.Plan plan
                    && (!signal.type().equals(com.imdomestic.chorus.effect.finisher.Finisher.PULSE) || !plan.timerId().equals(id)
                    || dueAt <= plan.startedAt() || dueAt > plan.dueAt() || remaining != 1 || intervalMicros != 0
                    || lifetime.isPresent() || pausedRemaining.isPresent()))
                throw new IllegalArgumentException("Finisher differs from its detached watchdog");
            if (signal.payload() instanceof com.imdomestic.chorus.effect.projectile.ShotGroups.Handle shot
                    && (!signal.type().equals(com.imdomestic.chorus.effect.projectile.ShotGroups.DUE) || !shot.timerId().equals(id)
                    || dueAt != shot.dueAt() || remaining != 1 || intervalMicros != 0 || lifetime.isPresent() || pausedRemaining.isPresent()))
                throw new IllegalArgumentException("Shot differs from its detached one-shot deadline");
            if (signal.payload() instanceof com.imdomestic.chorus.effect.combat.DamageGroups.Handle group
                    && (!signal.type().equals(com.imdomestic.chorus.effect.combat.DamageGroups.DUE) || !group.timerId().equals(id)
                    || dueAt != group.dueAt() || remaining != 1 || intervalMicros != 0 || lifetime.isPresent() || pausedRemaining.isPresent()))
                throw new IllegalArgumentException("Damage group differs from its detached deadline");
            if (signal.payload() instanceof com.imdomestic.chorus.effect.weapon.WeaponCharge.Plan charge
                    && (!signal.type().equals(com.imdomestic.chorus.effect.weapon.WeaponCharge.DUE) || !charge.timerId().equals(id)
                    || charge.phase() == com.imdomestic.chorus.effect.weapon.WeaponCharge.Phase.READY || dueAt != charge.dueAt()
                    || remaining != 1 || intervalMicros != 0 || lifetime.isPresent() || pausedRemaining.isPresent()))
                throw new IllegalArgumentException("Charge timer differs from its accepted full boundary");
        }
        public Timer(String id, long dueAt, long intervalMicros, int remaining, RuleEngine.Signal signal, Optional<Lifetime> lifetime) {
            this(id, dueAt, intervalMicros, remaining, signal, lifetime, OptionalLong.empty());
        }
    }
    public EffectState {
        actors = Collections.unmodifiableMap(new TreeMap<>(actors));
        actors.forEach((holder, binding) -> { com.imdomestic.chorus.effect.actor.ActorSources.instance(holder); Objects.requireNonNull(binding); });
        attachments = Collections.unmodifiableMap(new TreeMap<>(attachments));
        for (var entry : attachments.entrySet()) if (!entry.getKey().equals(entry.getValue().handle().id())
                || entry.getValue().handle().startedAt() > buffs.timeMicros() || entry.getValue().handle().expiresAt() <= buffs.timeMicros())
            throw new IllegalArgumentException("Wrong attachment identity or clock");
        Objects.requireNonNull(buffs);
        Objects.requireNonNull(mode); Objects.requireNonNull(random);
        charging = Collections.unmodifiableMap(new TreeMap<>(charging));
        for (var entry : charging.entrySet()) if (!entry.getKey().equals(entry.getValue().holder()) || entry.getValue().startTime() > buffs.timeMicros())
            throw new IllegalArgumentException("Wrong charge identity or clock");
        aiming = Collections.unmodifiableMap(new TreeMap<>(aiming));
        for (var entry : aiming.entrySet()) if (!entry.getKey().equals(entry.getValue().holder()) || entry.getValue().startTime() > buffs.timeMicros())
            throw new IllegalArgumentException("Wrong aim identity or clock");
        handling = Collections.unmodifiableMap(new TreeMap<>(handling));
        for (var entry : handling.entrySet()) if (!entry.getKey().equals(entry.getValue().holder()) || entry.getValue().startTime() > buffs.timeMicros())
            throw new IllegalArgumentException("Wrong handling identity or clock");
        damageTallies = Collections.unmodifiableMap(new TreeMap<>(damageTallies));
        for (var entry : damageTallies.entrySet()) if (!entry.getKey().equals(entry.getValue().handle().id())
                || entry.getValue().handle().startedAt() > buffs.timeMicros() || entry.getValue().handle().dueAt() <= buffs.timeMicros())
            throw new IllegalArgumentException("Wrong damage tally identity or clock");
        retainedCosts = Collections.unmodifiableMap(new TreeMap<>(retainedCosts));
        for (var entry : retainedCosts.entrySet()) if (!entry.getKey().equals(entry.getValue().handle().id())
                || entry.getValue().handle().startedAt() > buffs.timeMicros() || entry.getValue().handle().dueAt() <= buffs.timeMicros())
            throw new IllegalArgumentException("Wrong retained cost identity or clock");
        damageGroups = Collections.unmodifiableMap(new TreeMap<>(damageGroups));
        for (var entry : damageGroups.entrySet()) if (!entry.getKey().equals(entry.getValue().handle().id()) || entry.getValue().handle().startedAt() > buffs.timeMicros())
            throw new IllegalArgumentException("Wrong damage group identity or clock");
        shotGroups = Collections.unmodifiableMap(new TreeMap<>(shotGroups));
        for (var entry : shotGroups.entrySet()) if (!entry.getKey().equals(entry.getValue().handle().id()) || entry.getValue().handle().startedAt() > buffs.timeMicros())
            throw new IllegalArgumentException("Wrong shot group identity or clock");
        shots = Collections.unmodifiableMap(new TreeMap<>(shots));
        for (var entry : shots.entrySet()) if (!entry.getKey().equals(entry.getValue().gear().instance()) || entry.getValue().firedTime() > buffs.timeMicros())
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
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups, Map<String, com.imdomestic.chorus.effect.resource.RetainedCosts.Entry> retainedCosts, Map<String, com.imdomestic.chorus.effect.combat.DamageTallies.Entry> damageTallies, Map<String, com.imdomestic.chorus.effect.weapon.WeaponHandling.Plan> handling, Map<String, com.imdomestic.chorus.effect.weapon.WeaponAim.Plan> aiming, Map<String, com.imdomestic.chorus.effect.target.EntityAttachments.Entry> attachments, Map<String, com.imdomestic.chorus.effect.weapon.WeaponCharge.Plan> charging) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, Map.of());
    }
    public EffectState withActor(String holder, Optional<com.imdomestic.chorus.effect.actor.ActorBinding> binding) {
        com.imdomestic.chorus.effect.actor.ActorSources.instance(holder);
        var copy = new TreeMap<>(actors);
        if (binding.isPresent()) copy.put(holder, binding.orElseThrow()); else copy.remove(holder);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, copy);
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups, Map<String, com.imdomestic.chorus.effect.resource.RetainedCosts.Entry> retainedCosts, Map<String, com.imdomestic.chorus.effect.combat.DamageTallies.Entry> damageTallies, Map<String, com.imdomestic.chorus.effect.weapon.WeaponHandling.Plan> handling, Map<String, com.imdomestic.chorus.effect.weapon.WeaponAim.Plan> aiming) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups, Map<String, com.imdomestic.chorus.effect.resource.RetainedCosts.Entry> retainedCosts, Map<String, com.imdomestic.chorus.effect.combat.DamageTallies.Entry> damageTallies, Map<String, com.imdomestic.chorus.effect.weapon.WeaponHandling.Plan> handling, Map<String, com.imdomestic.chorus.effect.weapon.WeaponAim.Plan> aiming, Map<String, com.imdomestic.chorus.effect.target.EntityAttachments.Entry> attachments) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, Map.of());
    }
    public EffectState withCharge(com.imdomestic.chorus.effect.weapon.WeaponCharge.Plan plan) {
        var copy = new TreeMap<>(charging); copy.put(plan.holder(), plan);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, copy, actors);
    }
    public EffectState withoutCharge(String holder) {
        var copy = new TreeMap<>(charging); copy.remove(holder);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, copy, actors);
    }
    public EffectState withAttachments(Map<String, com.imdomestic.chorus.effect.target.EntityAttachments.Entry> value) {
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, value, charging, actors);
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups, Map<String, com.imdomestic.chorus.effect.resource.RetainedCosts.Entry> retainedCosts, Map<String, com.imdomestic.chorus.effect.combat.DamageTallies.Entry> damageTallies, Map<String, com.imdomestic.chorus.effect.weapon.WeaponHandling.Plan> handling) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, Map.of());
    }
    public EffectState withAim(com.imdomestic.chorus.effect.weapon.WeaponAim.Plan value) {
        var copy = new TreeMap<>(aiming); copy.put(value.holder(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, copy, attachments, charging, actors);
    }
    public EffectState withoutAim(String holder) {
        var copy = new TreeMap<>(aiming); copy.remove(holder);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, copy, attachments, charging, actors);
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups, Map<String, com.imdomestic.chorus.effect.resource.RetainedCosts.Entry> retainedCosts, Map<String, com.imdomestic.chorus.effect.combat.DamageTallies.Entry> damageTallies) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups, Map<String, com.imdomestic.chorus.effect.resource.RetainedCosts.Entry> retainedCosts) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers,
        Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups, Map<String, com.imdomestic.chorus.effect.combat.DamageGroups.Group> damageGroups) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers, Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random, Map<String, com.imdomestic.chorus.effect.projectile.ShotGroups.Group> shotGroups) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers, Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots, com.imdomestic.chorus.effect.random.RandomState random) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, Map.of());
    }
    public EffectState(BuffStore buffs, Map<ResourceState.Key, ResourceState> resources, Map<String, Timer> timers, Map<String, EffectSource> sources, Mode mode, Map<String, com.imdomestic.chorus.effect.equipment.Loadout> equipment, Map<String, com.imdomestic.chorus.effect.ability.AbilityLoadout> abilities, Map<String, com.imdomestic.chorus.effect.ammo.AmmoState> ammunition, Map<String, com.imdomestic.chorus.effect.weapon.WeaponReload.Plan> reloads, Map<String, com.imdomestic.chorus.effect.weapon.WeaponFire.Shot> shots) {
        this(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, com.imdomestic.chorus.effect.random.RandomState.ZERO);
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
    public EffectState withHandling(com.imdomestic.chorus.effect.weapon.WeaponHandling.Plan plan) {
        var copy = new TreeMap<>(handling); copy.put(plan.holder(), plan);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, copy, aiming, attachments, charging, actors);
    }
    public EffectState withoutHandling(String holder) {
        var copy = new TreeMap<>(handling); copy.remove(holder);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, copy, aiming, attachments, charging, actors);
    }
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
        return new EffectState(value, resources, updated, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withDamageTally(com.imdomestic.chorus.effect.combat.DamageTallies.Entry value) {
        var copy = new TreeMap<>(damageTallies); copy.put(value.handle().id(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, copy, handling, aiming, attachments, charging, actors);
    }
    public EffectState withoutDamageTally(String id) {
        var copy = new TreeMap<>(damageTallies); copy.remove(id);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, copy, handling, aiming, attachments, charging, actors);
    }
    public EffectState withRetainedCost(com.imdomestic.chorus.effect.resource.RetainedCosts.Entry value) {
        var copy = new TreeMap<>(retainedCosts); copy.put(value.handle().id(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, copy, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withoutRetainedCost(String id) {
        var copy = new TreeMap<>(retainedCosts); copy.remove(id);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, copy, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withDamageGroup(com.imdomestic.chorus.effect.combat.DamageGroups.Group value) {
        var copy = new TreeMap<>(damageGroups); copy.put(value.handle().id(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, copy, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withoutDamageGroup(String id) {
        var copy = new TreeMap<>(damageGroups); copy.remove(id);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, copy, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withShotGroup(com.imdomestic.chorus.effect.projectile.ShotGroups.Group value) {
        var copy = new TreeMap<>(shotGroups); copy.put(value.handle().id(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, copy, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withoutShotGroup(String id) {
        var copy = new TreeMap<>(shotGroups); copy.remove(id);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, copy, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withRandom(com.imdomestic.chorus.effect.random.RandomState value) {
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, value, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withShot(com.imdomestic.chorus.effect.weapon.WeaponFire.Shot value) {
        var copy = new TreeMap<>(shots); copy.put(value.gear().instance(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, reloads, copy, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withReload(com.imdomestic.chorus.effect.weapon.WeaponReload.Plan value) {
        var copy = new TreeMap<>(reloads); copy.put(value.holder(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, copy, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withoutReload(String holder) {
        var copy = new TreeMap<>(reloads); copy.remove(holder);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, ammunition, copy, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withAmmo(com.imdomestic.chorus.effect.ammo.AmmoState value) {
        var copy = new TreeMap<>(ammunition); copy.put(value.weapon(), value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, abilities, copy, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withResource(ResourceState value) {
        var copy = new HashMap<>(resources); copy.put(value.key(), value); return new EffectState(buffs, copy, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withResources(Map<ResourceState.Key, ResourceState> values) {
        return new EffectState(buffs, values, timers, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState schedule(Timer timer) {
        var copy = new TreeMap<>(timers);
        if (copy.putIfAbsent(timer.id(), timer) != null) throw new IllegalArgumentException("Duplicate timer identity: " + timer.id());
        return new EffectState(buffs, resources, copy, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState cancel(String id) { var copy = new TreeMap<>(timers); copy.remove(id); return new EffectState(buffs, resources, copy, sources, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors); }
    public EffectState withSource(EffectSource source) {
        var copy = new TreeMap<>(sources); copy.put(source.instance(), source); return withSources(copy);
    }
    public EffectState withoutSource(String instance) {
        var copy = new TreeMap<>(sources); copy.remove(instance); return withSources(copy);
    }
    public EffectState withSources(Map<String, EffectSource> value) {
        var updated = new TreeMap<>(timers); updated.values().removeIf(timer -> !EffectTimers.active(timer, value, buffs));
        return new EffectState(buffs, resources, updated, value, mode, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withEquipment(String holder, com.imdomestic.chorus.effect.equipment.Loadout value) {
        var updated = new TreeMap<>(equipment);
        if (value.equals(com.imdomestic.chorus.effect.equipment.Loadout.EMPTY)) updated.remove(holder); else updated.put(holder, value);
        return new EffectState(buffs, resources, timers, sources, mode, updated, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withAbilities(String holder, com.imdomestic.chorus.effect.ability.AbilityLoadout value) {
        var updated = new TreeMap<>(abilities);
        if (value.equals(com.imdomestic.chorus.effect.ability.AbilityLoadout.EMPTY)) updated.remove(holder); else updated.put(holder, value);
        return new EffectState(buffs, resources, timers, sources, mode, equipment, updated, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors);
    }
    public EffectState withMode(Mode value) { return new EffectState(buffs, resources, timers, sources, value, equipment, abilities, ammunition, reloads, shots, random, shotGroups, damageGroups, retainedCosts, damageTallies, handling, aiming, attachments, charging, actors); }
}
