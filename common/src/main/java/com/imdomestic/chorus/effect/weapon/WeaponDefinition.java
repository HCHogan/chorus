package com.imdomestic.chorus.effect.weapon;

import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.data.Value;
import com.imdomestic.chorus.effect.data.Condition;
import com.imdomestic.chorus.effect.data.EffectProgram;
import java.util.*;

/** Weapon behavior belongs to an equipment prototype; ammunition belongs to its physical instance. */
public record WeaponDefinition(String item, Ammunition ammunition, Reload reload, Optional<Fire> fire, Optional<Handling> handling, Optional<Aiming> aiming, Optional<Charging> charging) {
    public WeaponDefinition {
        id(item); Objects.requireNonNull(ammunition); Objects.requireNonNull(reload); Objects.requireNonNull(fire); Objects.requireNonNull(handling); Objects.requireNonNull(aiming); Objects.requireNonNull(charging);
        if (charging.isPresent() && fire.isEmpty()) throw new IllegalArgumentException("Charging requires a fire definition");
    }
    public WeaponDefinition(String item, Ammunition ammunition, Reload reload, Optional<Fire> fire, Optional<Handling> handling, Optional<Aiming> aiming) { this(item, ammunition, reload, fire, handling, aiming, Optional.empty()); }
    public WeaponDefinition(String item, Ammunition ammunition, Reload reload, Optional<Fire> fire) { this(item, ammunition, reload, fire, Optional.empty()); }
    public WeaponDefinition(String item, Ammunition ammunition, Reload reload, Optional<Fire> fire, Optional<Handling> handling) { this(item, ammunition, reload, fire, handling, Optional.empty()); }
    public WeaponDefinition(String item, Ammunition ammunition, Reload reload) { this(item, ammunition, reload, Optional.empty()); }
    /** Each phase has its own stat-to-duration pipeline. Omitted handling retains instantaneous selection. */
    public record Handling(Timing ready, Timing stow) {
        public Handling { Objects.requireNonNull(ready); Objects.requireNonNull(stow); }
    }
    /** Both edges are explicit; zero-duration aim phases complete at the same logical boundary. */
    public record Aiming(Timing enter, Timing exit) {
        public Aiming { Objects.requireNonNull(enter); Objects.requireNonNull(exit); }
    }
    /** Release-triggered charging; both timings are sampled when the press is accepted. */
    public record Charging(Timing minimum, Timing full, Condition condition) {
        public Charging { Objects.requireNonNull(minimum); Objects.requireNonNull(full); Objects.requireNonNull(condition); }
    }
    /** One accepted shot; charging supplies release measurements, while burst and hit aggregation remain separate. */
    public record Fire(Value cost, Value interval, Optional<String> intervalProfile, Condition condition, Set<String> tags, List<EffectProgram.Step> onFire) {
        public Fire {
            Objects.requireNonNull(cost); Objects.requireNonNull(interval); Objects.requireNonNull(intervalProfile); Objects.requireNonNull(condition);
            intervalProfile.ifPresent(WeaponDefinition::id); tags = Set.copyOf(tags); tags.forEach(WeaponDefinition::id); onFire = List.copyOf(onFire);
        }
    }
    public record Ammunition(int capacity, int magazine, Optional<AmmoState.Reserve> reserves, Optional<String> capacityProfile) {
        public Ammunition {
            if (capacity < 1 || magazine < 0) throw new IllegalArgumentException("Invalid initial weapon ammunition");
            Objects.requireNonNull(reserves); Objects.requireNonNull(capacityProfile); capacityProfile.ifPresent(WeaponDefinition::id);
        }
        public AmmoState initialize(String holder, String weapon) {
            return new AmmoState(weapon, magazine, capacity, reserves, capacityProfile.map(id -> new AmmoState.CapacityProfile(holder, id)));
        }
        /** Re-equipping retains counts. A new holder changes whose capacity modifiers are queried. */
        public AmmoState adopt(String holder, AmmoState account) {
            if (capacity != account.capacity() || !reserves.map(AmmoState.Reserve::capacity).equals(account.reserve().map(AmmoState.Reserve::capacity))
                    || !capacityProfile.equals(account.capacityProfile().map(AmmoState.CapacityProfile::profile)))
                throw new IllegalArgumentException("Weapon ammunition definition differs from its existing account");
            return new AmmoState(account.weapon(), account.magazine(), account.capacity(), account.reserve(), capacityProfile.map(id -> new AmmoState.CapacityProfile(holder, id)));
        }
    }
    /** The optional ordered pipeline starts with value's unit and must finish in seconds. */
    public record Reload(Value value, List<String> profiles, Optional<Insert> insert) {
        public Reload { Objects.requireNonNull(value); profiles = List.copyOf(profiles); profiles.forEach(WeaponDefinition::id); Objects.requireNonNull(insert); }
        public Reload(Value value, List<String> profiles) { this(value, profiles, Optional.empty()); }
        public Reload(Value value, Optional<String> profile) { this(value, profile.stream().toList()); }
    }
    /** Each insertion is its own accepted timed operation. Repeat timing is separate from the first insertion. */
    public record Insert(Value rounds, Optional<String> roundsProfile, Timing repeat) {
        public Insert { Objects.requireNonNull(rounds); Objects.requireNonNull(roundsProfile); roundsProfile.ifPresent(WeaponDefinition::id); Objects.requireNonNull(repeat); }
    }
    public record Timing(Value value, List<String> profiles) {
        public Timing { Objects.requireNonNull(value); profiles = List.copyOf(profiles); profiles.forEach(WeaponDefinition::id); }
    }
    private static void id(String id) {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid weapon definition id");
    }
}
