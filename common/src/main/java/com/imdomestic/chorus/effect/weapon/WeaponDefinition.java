package com.imdomestic.chorus.effect.weapon;

import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.data.Value;
import java.util.*;

/** Weapon behavior belongs to an equipment prototype; ammunition belongs to its physical instance. */
public record WeaponDefinition(String item, Ammunition ammunition, Reload reload) {
    public WeaponDefinition {
        id(item); Objects.requireNonNull(ammunition); Objects.requireNonNull(reload);
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
    /** With a profile, value has the profile's input unit; its output must be seconds. */
    public record Reload(Value value, Optional<String> profile) {
        public Reload { Objects.requireNonNull(value); Objects.requireNonNull(profile); profile.ifPresent(WeaponDefinition::id); }
    }
    private static void id(String id) {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid weapon definition id");
    }
}
