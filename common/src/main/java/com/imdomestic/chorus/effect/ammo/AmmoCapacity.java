package com.imdomestic.chorus.effect.ammo;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.CalculationProfile;
import java.util.*;

/** Derived capacity never overwrites the immutable profile input or changes the number of loaded rounds. */
public final class AmmoCapacity {
    private AmmoCapacity() {}
    public record View(AmmoState account, int capacity, Optional<CalculationProfile.Result> calculation) {
        public View {
            Objects.requireNonNull(account); Objects.requireNonNull(calculation);
            if (capacity < 1 || calculation.isPresent() != account.capacityProfile().isPresent()) throw new IllegalArgumentException("Invalid ammunition capacity view");
            if (calculation.isEmpty() && capacity != account.capacity()) throw new IllegalArgumentException("Fixed ammunition capacity differs");
            if (calculation.isPresent() && (!calculation.orElseThrow().output().unit().equals(com.imdomestic.chorus.stat.Unit.ROUND)
                    || !calculation.orElseThrow().inputs().base().equals(new com.imdomestic.chorus.stat.Measure(account.capacity(), com.imdomestic.chorus.stat.Unit.ROUND))
                    || AmmoState.rounds(calculation.orElseThrow().output().value()) != capacity
                    || !calculation.orElseThrow().trace().profile().equals(account.capacityProfile().orElseThrow().profile()))) throw new IllegalArgumentException("Capacity calculation disagrees with view");
        }
        public static View fixed(AmmoState account) { return new View(account, account.capacity(), Optional.empty()); }
        public int read(AmmoState.Field field) {
            return switch (field) { case CAPACITY -> capacity; case MISSING -> Math.max(0, capacity - account.magazine()); default -> account.read(field); };
        }
        /** Operation results retain the capacity used for that operation, even if a later query would differ. */
        public View after(AmmoState next) {
            if (!account.weapon().equals(next.weapon()) || account.capacity() != next.capacity() || !account.capacityProfile().equals(next.capacityProfile())) throw new IllegalArgumentException("Changed ammunition capacity definition");
            return new View(next, capacity, calculation);
        }
    }
    /** Immutable dependency path diagnoses undefined numerical self-reference; it does not constrain event chains. */
    public record Query(EffectEvent event, Set<String> dependencies) implements EffectEvent.Carrier {
        public Query { Objects.requireNonNull(event); dependencies = Set.copyOf(dependencies); }
        public static Set<String> dependencies(RuleEngine.Payload payload) { return com.imdomestic.chorus.effect.data.NumericQuery.Path.from(payload).ammunition(); }
    }
}
